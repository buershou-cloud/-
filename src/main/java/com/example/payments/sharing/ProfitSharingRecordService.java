package com.example.payments.sharing;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.gateway.douyin.DouyinProfitSharingState;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.example.payments.order.OrderOperationView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Outgoing splits are separate from payment orders, including splits of external transactions. */
@Service
public class ProfitSharingRecordService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final DemoOrderService orders;
    private final ObjectMapper json;
    private final Map<String, Record> memory = new LinkedHashMap<>();

    @Autowired
    public ProfitSharingRecordService(ObjectProvider<JdbcTemplate> provider, DemoOrderService orders, ObjectMapper mapper) {
        this(provider.getIfAvailable(), orders, mapper);
    }

    public ProfitSharingRecordService(DemoOrderService orders) {
        this((JdbcTemplate) null, orders, new ObjectMapper());
    }

    ProfitSharingRecordService(JdbcTemplate jdbc, DemoOrderService orders, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        if (jdbc != null) {
            initializeTable();
        }
    }

    private void initializeTable() {
        try {
            jdbc.execute("""
                    SELECT channel_id,out_request_no,provider,out_trade_no,trade_no,merchant_id,merchant_name,subject,
                           share_amount,recipient,status,code,message,raw_request,raw_response,created_at,updated_at
                    FROM profit_sharing_record WHERE 1=0
                    """);
        } catch (DataAccessException ex) {
            if (!missingTable(ex)) throw ex;
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS profit_sharing_record (
                      channel_id VARCHAR(64) NOT NULL, out_request_no VARCHAR(96) NOT NULL,
                      provider VARCHAR(32) NOT NULL, out_trade_no VARCHAR(96), trade_no VARCHAR(128),
                      merchant_id VARCHAR(64), merchant_name VARCHAR(255), subject VARCHAR(255),
                      share_amount DECIMAL(18,2), recipient VARCHAR(1024),
                      status VARCHAR(32) NOT NULL, code VARCHAR(128), message VARCHAR(2048),
                      raw_request LONGTEXT, raw_response LONGTEXT,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (channel_id, out_request_no),
                      INDEX idx_profit_sharing_record_created (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
                    """);
        }
    }

    private static boolean missingTable(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if ("42S02".equals(sql.getSQLState())) return true;
                // H2 distinguishes an entirely empty database from an absent table in a populated one.
                return "42S04".equals(sql.getSQLState()) && sql.getErrorCode() == 42104
                        && sql.getClass().getName().startsWith("org.h2.jdbc.");
            }
        }
        return false;
    }

    /** Persist before sending money. Completed identical requests can be answered without another transfer. */
    public synchronized GatewayResponse reserve(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request) {
        try {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("outTradeNo", request.outTradeNo());
            audit.put("tradeNo", request.tradeNo());
            audit.put("royaltyParameters", request.royaltyParameters());
            audit.put("operatorId", request.operatorId());
            audit.put("extra", request.extra());
            if ("ALIPAY".equals(channel.getProvider()) || "ALIPAY_DIRECT".equals(channel.getProvider())) {
                String token = first(request.appAuthToken(), channel.getAlipay().getAppAuthToken());
                audit.put("authorizationContext", has(token) ? digest(token) : null);
            }
            Record candidate = make(channel, required(request.outRequestNo()), request.outTradeNo(), request.tradeNo(),
                    amount(request.royaltyParameters(), false), recipient(request.royaltyParameters(), false), encode(audit));
            Insertion inserted = insert(candidate);
            Record current = inserted.record();
            if (!Objects.equals(current.request(), candidate.request())) {
                throw new ProfitSharingRecordException("PROFIT_SHARING_REQUEST_CONFLICT",
                        "该分账请求号已存在且原参数不一致或仅有历史查单记录；请先核对结果，不要重复划拨");
            }
            if ("SUCCESS".equals(current.status())) return response(current);
            if (!inserted.created()) {
                throw new ProfitSharingRecordException("PROFIT_SHARING_ALREADY_RECORDED",
                        "该分账请求已记录，请查询原请求或到支付平台核对；不会再次提交划拨，请勿更换请求号重复分账");
            }
            return null;
        } catch (ProfitSharingRecordException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProfitSharingRecordException("分账请求记录保存失败，尚未发送本次分账", ex);
        }
    }

    public synchronized void recordResponse(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request, GatewayResponse result) {
        saveResult(channel.getId(), request.outRequestNo(), result);
    }

    /** Called only after an actual provider query returned; this method never submits a split. */
    public synchronized void recordQuery(PaymentGatewayProperties.Channel channel, ProfitSharingQueryRequest request, GatewayResponse result) {
        if (result.raw() != null && "FINISH".equals(result.raw().get("profit_sharing_operation"))) return;
        try {
            Map<String, Object> data = data(result.raw());
            String transaction = first(result.tradeNo(), request.tradeNo());
            String returnedId = text(data, "out_order_no");
            if (returnedId != null && !returnedId.equals(request.outRequestNo())) {
                throw new IllegalArgumentException("分账查询返回的请求号不匹配");
            }
            insert(make(channel, required(request.outRequestNo()), first(request.outTradeNo(), result.outTradeNo()), transaction,
                    amount(receivers(data), true), recipient(receivers(data), true), null));
            saveResult(channel.getId(), request.outRequestNo(), result);
        } catch (ProfitSharingRecordException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProfitSharingRecordException("分账查询已返回，但本地记录保存失败；请使用原请求号继续查单", ex);
        }
    }

    /** The controller must verify/decrypt the whole split event before calling this method. */
    public synchronized void recordDouyinNotification(PaymentGatewayProperties.Channel channel, Map<String, Object> payload) {
        if (DouyinProfitSharingState.hasFinishEvidence(payload)) return;
        String id = required(text(payload, "out_order_no"));
        String transaction = required(text(payload, "transaction_id"));
        PaymentStatus status = DouyinProfitSharingState.toPaymentStatus(payload, false);
        GatewayResponse result = new GatewayResponse(channel.getId(), status, text(payload, "state"),
                "已接收抖音分账结果通知", null, transaction, null, null, payload, List.of());
        recordQuery(channel, new ProfitSharingQueryRequest(null, transaction, id, null, List.of(channel.getId()), Map.of()), result);
    }

    public synchronized List<OrderOperationView> search(String beginTime, String endTime, String orderNo, String tradeNo, String channelId) {
        List<Record> records;
        if (jdbc == null) {
            records = new ArrayList<>(memory.values());
        } else {
            StringBuilder sql = new StringBuilder("SELECT * FROM profit_sharing_record WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (has(orderNo)) {
                sql.append(" AND (out_request_no LIKE ? OR out_trade_no LIKE ?)");
                args.add("%" + orderNo.trim() + "%"); args.add("%" + orderNo.trim() + "%");
            }
            if (has(tradeNo)) { sql.append(" AND trade_no LIKE ?"); args.add("%" + tradeNo.trim() + "%"); }
            if (has(channelId)) { sql.append(" AND channel_id = ?"); args.add(channelId.trim()); }
            if (has(beginTime)) { sql.append(" AND created_at >= ?"); args.add(timestamp(beginTime)); }
            if (has(endTime)) { sql.append(" AND created_at <= ?"); args.add(timestamp(endTime)); }
            sql.append(" ORDER BY created_at DESC, out_request_no DESC");
            records = jdbc.query(sql.toString(), this::map, args.toArray());
        }
        return records.stream()
                .filter(r -> !has(orderNo) || contains(r.id(), orderNo) || contains(r.outTradeNo(), orderNo))
                .filter(r -> !has(tradeNo) || contains(r.tradeNo(), tradeNo))
                .filter(r -> !has(channelId) || channelId.trim().equals(r.channel()))
                .filter(r -> !has(beginTime) || !r.created().isBefore(timestamp(beginTime).toLocalDateTime()))
                .filter(r -> !has(endTime) || !r.created().isAfter(timestamp(endTime).toLocalDateTime()))
                .sorted(Comparator.comparing(Record::created).thenComparing(Record::id).reversed())
                .map(r -> new OrderOperationView("PROFIT_SHARING", r.id(), r.tradeNo(), r.outTradeNo(), r.channel(), r.provider(),
                        r.merchantId(), r.merchantName(), r.subject(), r.amount(), r.status(), TIME.format(r.created()), r.recipient(), r.message()))
                .toList();
    }

    private Record make(PaymentGatewayProperties.Channel channel, String id, String outTradeNo, String tradeNo,
                        BigDecimal amount, String recipient, String request) {
        DemoOrderView order = localOrder(outTradeNo, tradeNo, channel.getId());
        return new Record(channel.getId(), id, channel.getProvider(), first(outTradeNo, order == null ? null : order.outTradeNo()),
                first(tradeNo, order == null ? null : order.tradeNo()), order == null ? null : order.merchantId(),
                order == null ? null : order.merchantName(), "分账", amount, recipient, "PENDING", null,
                "分账结果待确认，请使用原请求号核对", request, null, LocalDateTime.now());
    }

    private DemoOrderView localOrder(String outTradeNo, String tradeNo, String channelId) {
        if (has(outTradeNo)) {
            try {
                DemoOrderView order = orders.view(outTradeNo);
                return order != null && channelId.equals(order.channelId()) ? order : null;
            } catch (IllegalArgumentException ignored) { return null; }
        }
        if (has(tradeNo)) {
            return orders.search(null, null, null, tradeNo, channelId).stream()
                    .filter(o -> tradeNo.equals(o.tradeNo())).findFirst().orElse(null);
        }
        return null;
    }

    private Insertion insert(Record r) {
        Record existing = find(r.channel(), r.id());
        if (existing != null) {
            validateIdentity(existing, r.tradeNo());
            return new Insertion(r.request() == null ? enrich(existing, r) : existing, false);
        }
        if (jdbc == null) {
            memory.put(key(r.channel(), r.id()), r);
            return new Insertion(r, true);
        }
        try {
            jdbc.update("""
                    INSERT INTO profit_sharing_record
                    (channel_id,out_request_no,provider,out_trade_no,trade_no,merchant_id,merchant_name,subject,
                     share_amount,recipient,status,code,message,raw_request,raw_response,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, r.channel(), r.id(), r.provider(), r.outTradeNo(), r.tradeNo(), r.merchantId(), r.merchantName(),
                    r.subject(), r.amount(), r.recipient(), r.status(), r.code(), r.message(), r.request(), r.response(),
                    Timestamp.valueOf(r.created()), Timestamp.valueOf(r.created()));
            return new Insertion(r, true);
        } catch (DuplicateKeyException ex) {
            Record winner = find(r.channel(), r.id());
            if (winner == null) throw ex;
            validateIdentity(winner, r.tradeNo());
            return new Insertion(r.request() == null ? enrich(winner, r) : winner, false);
        }
    }

    /** Verified observations can fill missing history, including after SUCCESS, without rewriting the original allocation. */
    private Record enrich(Record current, Record observation) {
        if (jdbc == null) {
            Record updated = new Record(current.channel(), current.id(), current.provider(), first(current.outTradeNo(), observation.outTradeNo()),
                    first(current.tradeNo(), observation.tradeNo()), first(current.merchantId(), observation.merchantId()),
                    first(current.merchantName(), observation.merchantName()), current.subject(),
                    current.amount() == null ? observation.amount() : current.amount(), first(current.recipient(), observation.recipient()),
                    current.status(), current.code(), current.message(), current.request(), current.response(), current.created());
            memory.put(key(current.channel(), current.id()), updated);
            return updated;
        }
        jdbc.update("""
                UPDATE profit_sharing_record SET out_trade_no=COALESCE(out_trade_no,?), trade_no=COALESCE(trade_no,?),
                merchant_id=COALESCE(merchant_id,?), merchant_name=COALESCE(merchant_name,?),
                share_amount=COALESCE(share_amount,?), recipient=COALESCE(recipient,?)
                WHERE channel_id=? AND out_request_no=? AND (trade_no IS NULL OR ? IS NULL OR trade_no=?)
                """, observation.outTradeNo(), observation.tradeNo(), observation.merchantId(), observation.merchantName(),
                observation.amount(), observation.recipient(), current.channel(), current.id(), observation.tradeNo(), observation.tradeNo());
        Record updated = find(current.channel(), current.id());
        validateIdentity(updated, observation.tradeNo());
        return updated;
    }

    private void saveResult(String channelId, String id, GatewayResponse result) {
        try {
            Record current = find(channelId, id);
            if (current == null) throw new IllegalStateException("Missing reserved profit-sharing record");
            if (has(result.channelId()) && !channelId.equals(result.channelId())) throw new IllegalArgumentException("分账响应通道不匹配");
            validateIdentity(current, result.tradeNo());
            String status = switch (result.status()) {
                case SUCCESS -> "SUCCESS";
                case FAILED, CLOSED -> "FAILED";
                default -> "PENDING";
            };
            String raw = encode(result.raw() == null ? Map.of() : result.raw());
            if (jdbc == null) {
                if ("SUCCESS".equals(current.status()) || ("FAILED".equals(current.status()) && "PENDING".equals(status))) return;
                memory.put(key(channelId, id), new Record(current.channel(), current.id(), current.provider(), current.outTradeNo(),
                        first(current.tradeNo(), result.tradeNo()), current.merchantId(), current.merchantName(), current.subject(),
                        current.amount(), current.recipient(), status, result.code(), result.message(), current.request(), raw, current.created()));
            } else {
                // The WHERE guard runs atomically in the database, including concurrent callback/HTTP responses.
                jdbc.update("""
                        UPDATE profit_sharing_record SET trade_no=COALESCE(trade_no,?), status=?, code=?, message=?,
                        raw_response=?, updated_at=CURRENT_TIMESTAMP
                        WHERE channel_id=? AND out_request_no=? AND status <> 'SUCCESS'
                          AND (status <> 'FAILED' OR ? <> 'PENDING')
                        """, result.tradeNo(), status, result.code(), result.message(), raw, channelId, id, status);
            }
        } catch (ProfitSharingRecordException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ProfitSharingRecordException("平台分账已返回，但本地记录保存失败；不要新增分账，请用原请求号核对结果", ex);
        }
    }

    private Record find(String channel, String id) {
        if (jdbc == null) return memory.get(key(channel, id));
        return jdbc.query("SELECT * FROM profit_sharing_record WHERE channel_id=? AND out_request_no=?", this::map, channel, id)
                .stream().findFirst().orElse(null);
    }

    private Record map(ResultSet rs, int row) throws SQLException {
        return new Record(rs.getString("channel_id"), rs.getString("out_request_no"), rs.getString("provider"),
                rs.getString("out_trade_no"), rs.getString("trade_no"), rs.getString("merchant_id"), rs.getString("merchant_name"),
                rs.getString("subject"), rs.getBigDecimal("share_amount"), rs.getString("recipient"), rs.getString("status"),
                rs.getString("code"), rs.getString("message"), rs.getString("raw_request"), rs.getString("raw_response"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private GatewayResponse response(Record r) {
        try {
            Map<String, Object> raw = r.response() == null ? Map.of() : json.readValue(r.response(), MAP);
            return new GatewayResponse(r.channel(), PaymentStatus.SUCCESS, r.code(), r.message(), r.outTradeNo(), r.tradeNo(), null, null, raw, List.of());
        } catch (Exception ex) { throw new IllegalStateException("Cannot read saved profit-sharing result", ex); }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Cannot serialize profit-sharing record", ex); }
    }

    private static void validateIdentity(Record current, String tradeNo) {
        if (has(current.tradeNo()) && has(tradeNo) && !current.tradeNo().equals(tradeNo)) {
            throw new ProfitSharingRecordException("PROFIT_SHARING_REQUEST_CONFLICT", "分账请求号对应的原支付交易不匹配");
        }
    }

    private static BigDecimal amount(List<Map<String, Object>> receivers, boolean fen) {
        if (receivers == null || receivers.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> receiver : receivers) {
            if (receiver == null || receiver.get("amount") == null) return null;
            total = total.add(new BigDecimal(receiver.get("amount").toString()));
        }
        return fen ? total.movePointLeft(2) : total;
    }

    private static String recipient(List<Map<String, Object>> receivers, boolean observed) {
        if (receivers == null) return null;
        return receivers.stream().filter(Objects::nonNull).map(r -> text(r, observed ? "account" : "trans_in"))
                .filter(Objects::nonNull).map(ProfitSharingRecordService::mask).distinct().reduce((a, b) -> a + ", " + b).orElse(null);
    }

    private static String mask(String value) {
        return value.length() <= 4 ? "****" : value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        return raw.get("data") instanceof Map<?, ?> nested ? (Map<String, Object>) nested : raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> receivers(Map<String, Object> data) {
        return data.get("receivers") instanceof List<?> list && list.stream().allMatch(item -> item instanceof Map<?, ?>)
                ? (List<Map<String, Object>>) list : List.of();
    }

    private static Timestamp timestamp(String value) {
        String normalized = value.trim().replace('T', ' ');
        if (normalized.length() == 10) normalized += " 00:00:00";
        if (normalized.length() == 16) normalized += ":00";
        return Timestamp.valueOf(normalized);
    }
    private static boolean contains(String value, String term) { return value != null && value.contains(term.trim()); }
    private static String required(String value) { if (!has(value)) throw new IllegalArgumentException("分账请求号或交易号不能为空"); return value.trim(); }
    private static String text(Map<String, Object> map, String key) { Object v = map.get(key); return v == null ? null : v.toString(); }
    private static String first(String a, String b) { return has(a) ? a : b; }
    private static boolean has(String value) { return value != null && !value.isBlank(); }
    private static String key(String channel, String id) { return channel + "\n" + id; }
    private record Record(String channel, String id, String provider, String outTradeNo, String tradeNo, String merchantId,
                          String merchantName, String subject, BigDecimal amount, String recipient, String status,
                          String code, String message, String request, String response, LocalDateTime created) { }
    private record Insertion(Record record, boolean created) { }
}
