package com.example.payments.order;

import com.example.payments.gateway.douyin.DouyinRefundEvidence;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.merchant.api.MerchantApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class DemoOrderService {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderService.class);
    private static final DateTimeFormatter SERIAL_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, DemoOrder> orders = new LinkedHashMap<>();
    private final Map<String, MerchantNotifyTarget> merchantNotifyTargets = new LinkedHashMap<>();
    private final Map<String, MemoryRefund> memoryRefunds = new LinkedHashMap<>();
    private final Map<String, CaptureReservation> memoryCaptures = new LinkedHashMap<>();
    private final Map<String, UnfreezeReservation> memoryUnfreezes = new LinkedHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final ConfirmedReceiptSink confirmedReceiptSink;

    public DemoOrderService() {
        this((JdbcTemplate) null);
    }

    public DemoOrderService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(jdbcTemplateProvider.getIfAvailable());
    }

    @Autowired
    public DemoOrderService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                            ObjectProvider<ConfirmedReceiptSink> confirmedReceiptSinkProvider) {
        this(jdbcTemplateProvider.getIfAvailable(), confirmedReceiptSinkProvider.getIfAvailable());
    }

    private DemoOrderService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    DemoOrderService(JdbcTemplate jdbcTemplate, ConfirmedReceiptSink confirmedReceiptSink) {
        this.jdbcTemplate = jdbcTemplate;
        this.confirmedReceiptSink = confirmedReceiptSink;
        if (jdbcTemplate != null) {
            ensurePreauthUnfreezeTable();
            ensurePreauthCaptureTable();
        }
    }

    public synchronized List<DemoOrderView> recent() {
        return search(null, null, null, null, null);
    }

    public synchronized List<DemoOrderView> pendingDouyinOrders(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (databaseBacked()) {
            return jdbcTemplate.query("""
                            SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                                   COALESCE(m.name, o.merchant_id) AS merchant_name,
                                   o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                                   o.pre_authorization, o.supplemented, o.profit_shared,
                                   COALESCE((
                                       SELECT SUM(r.refund_amount)
                                       FROM refund_order r
                                       WHERE r.out_trade_no = o.out_trade_no
                                         AND r.status = 'SUCCESS'
                                   ), 0) AS refunded_amount,
                                   COALESCE((
                                       SELECT SUM(u.amount)
                                       FROM preauth_unfreeze_order u
                                       WHERE u.out_trade_no = o.out_trade_no
                                         AND u.status = 'SUCCESS'
                                   ), 0) AS preauth_unfrozen_amount
                            FROM pay_order o
                            LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                            INNER JOIN pay_channel c ON c.id = o.channel_id
                            WHERE o.status = 'UNPAID'
                              AND c.provider = 'DOUYIN'
                              AND o.created_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)
                            ORDER BY o.created_at DESC, o.out_trade_no DESC
                            LIMIT ?
                            """,
                            this::mapOrder,
                            safeLimit)
                    .stream()
                    .map(DemoOrderView::from)
                    .toList();
        }
        return orders.values().stream()
                .filter(order -> order.getStatus() == DemoOrderStatus.UNPAID)
                .filter(order -> hasText(order.getProductName()) && order.getProductName().contains("抖音"))
                .limit(safeLimit)
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized List<DouyinPendingRefund> pendingDouyinRefunds(int limit) {
        if (!databaseBacked()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                        SELECT r.channel_id, r.out_trade_no, r.out_request_no
                        FROM refund_order r
                        INNER JOIN pay_channel c ON c.id = r.channel_id
                        WHERE r.status = 'PENDING'
                          AND UPPER(c.provider) = 'DOUYIN'
                        ORDER BY r.updated_at ASC, r.id ASC
                        LIMIT ?
                        """,
                (resultSet, rowNum) -> new DouyinPendingRefund(
                        resultSet.getString("channel_id"),
                        resultSet.getString("out_trade_no"),
                        resultSet.getString("out_request_no")
                ),
                safeLimit
        );
    }

    public synchronized List<DemoOrderView> search(
            String beginTime,
            String endTime,
            String outTradeNo,
            String tradeNo,
            String channelId
    ) {
        if (databaseBacked()) {
            StringBuilder sql = new StringBuilder("""
                    SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                           COALESCE(m.name, o.merchant_id) AS merchant_name,
                           o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                           o.pre_authorization, o.supplemented, o.profit_shared,
                            COALESCE((
                                SELECT SUM(r.refund_amount)
                                FROM refund_order r
                                WHERE r.out_trade_no = o.out_trade_no
                                  AND r.status = 'SUCCESS'
                            ), 0) AS refunded_amount,
                            COALESCE((
                                SELECT SUM(u.amount)
                                FROM preauth_unfreeze_order u
                                WHERE u.out_trade_no = o.out_trade_no
                                  AND u.status = 'SUCCESS'
                            ), 0) AS preauth_unfrozen_amount
                    FROM pay_order o
                    LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                    WHERE 1 = 1
                    """);
            List<Object> args = new ArrayList<>();
            if (hasText(outTradeNo)) {
                sql.append(" AND o.out_trade_no LIKE ?");
                args.add("%" + outTradeNo.trim() + "%");
            }
            if (hasText(tradeNo)) {
                sql.append(" AND o.trade_no LIKE ?");
                args.add("%" + tradeNo.trim() + "%");
            }
            if (hasText(channelId)) {
                sql.append(" AND o.channel_id = ?");
                args.add(channelId.trim());
            }
            if (hasText(beginTime)) {
                sql.append(" AND o.created_at >= ?");
                args.add(normalizeTime(beginTime));
            }
            if (hasText(endTime)) {
                sql.append(" AND o.created_at <= ?");
                args.add(normalizeTime(endTime));
            }
            sql.append(" ORDER BY o.created_at DESC, o.out_trade_no DESC");
            return jdbcTemplate.query(sql.toString(), this::mapOrder, args.toArray()).stream()
                    .map(DemoOrderView::from)
                    .toList();
        }
        List<DemoOrder> filtered = orders.values().stream()
                .filter(order -> contains(order.getOutTradeNo(), outTradeNo))
                .filter(order -> contains(order.getTradeNo(), tradeNo))
                .filter(order -> !hasText(channelId) || Objects.equals(order.getChannelId(), channelId.trim()))
                .filter(order -> !hasText(beginTime) || compareTime(order.getCreatedAt(), beginTime) >= 0)
                .filter(order -> !hasText(endTime) || compareTime(order.getCreatedAt(), endTime) <= 0)
                .toList();
        return filtered.stream()
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized List<DemoOrderView> byMerchant(String merchantId) {
        if (databaseBacked()) {
            return jdbcTemplate.query("""
                            SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                                   COALESCE(m.name, o.merchant_id) AS merchant_name,
                                   o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                                   o.pre_authorization, o.supplemented, o.profit_shared,
                                    COALESCE((
                                        SELECT SUM(r.refund_amount)
                                        FROM refund_order r
                                        WHERE r.out_trade_no = o.out_trade_no
                                          AND r.status = 'SUCCESS'
                                    ), 0) AS refunded_amount,
                                    COALESCE((
                                        SELECT SUM(u.amount)
                                        FROM preauth_unfreeze_order u
                                        WHERE u.out_trade_no = o.out_trade_no
                                          AND u.status = 'SUCCESS'
                                    ), 0) AS preauth_unfrozen_amount
                            FROM pay_order o
                            LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                            WHERE o.merchant_id = ?
                            ORDER BY o.created_at DESC, o.out_trade_no DESC
                            """,
                            this::mapOrder,
                            merchantId)
                    .stream()
                    .map(DemoOrderView::from)
                    .toList();
        }
        return orders.values().stream()
                .filter(order -> Objects.equals(order.getMerchantId(), merchantId))
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized List<DemoOrderView> shareableByChannel(String channelId, boolean includeProfitShared) {
        if (!hasText(channelId)) {
            throw new IllegalArgumentException("鏀粯閫氶亾涓嶈兘涓虹┖");
        }
        if (databaseBacked()) {
            String sql = """
                    SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                           COALESCE(m.name, o.merchant_id) AS merchant_name,
                           o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                           o.pre_authorization, o.supplemented, o.profit_shared,
                            COALESCE((
                                SELECT SUM(r.refund_amount)
                                FROM refund_order r
                                WHERE r.out_trade_no = o.out_trade_no
                                  AND r.status = 'SUCCESS'
                            ), 0) AS refunded_amount,
                            COALESCE((
                                SELECT SUM(u.amount)
                                FROM preauth_unfreeze_order u
                                WHERE u.out_trade_no = o.out_trade_no
                                  AND u.status = 'SUCCESS'
                            ), 0) AS preauth_unfrozen_amount
                    FROM pay_order o
                    LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                    WHERE o.channel_id = ?
                      AND o.status = 'COMPLETED'
                      AND o.trade_no IS NOT NULL
                      AND (? = 1 OR o.profit_shared = 0)
                    ORDER BY o.created_at DESC, o.out_trade_no DESC
                    """;
            return jdbcTemplate.query(sql, this::mapOrder, channelId.trim(), includeProfitShared ? 1 : 0)
                    .stream()
                    .map(DemoOrderView::from)
                    .toList();
        }
        return orders.values().stream()
                .filter(order -> Objects.equals(order.getChannelId(), channelId.trim()))
                .filter(order -> order.getStatus() == DemoOrderStatus.COMPLETED)
                .filter(order -> hasText(order.getTradeNo()))
                .filter(order -> includeProfitShared || !order.isProfitShared())
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized DemoOrderView markProfitShared(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        order.setProfitShared(true);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView view(String outTradeNo) {
        return DemoOrderView.from(order(outTradeNo));
    }

    public synchronized DemoOrderView complete(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        DemoOrderStatus previous = order.getStatus();
        if (order.isPreAuthorization()) {
            order.setStatus(DemoOrderStatus.FROZEN);
            ensureTradeNo(order, "AUTH");
        } else {
            order.setStatus(DemoOrderStatus.COMPLETED);
            ensureTradeNo(order, "MANUAL");
        }
        markPaidTransition(order, previous);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView uncomplete(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.COMPLETED) {
            throw new IllegalStateException("鍙湁宸插畬鎴愯鍗曞彲浠ユ敼鏈畬鎴?");
        }
        order.setStatus(DemoOrderStatus.UNPAID);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView manualSupplement(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.UNPAID) {
            throw new IllegalStateException("鍙湁鏈敮浠樿鍗曞彲浠ユ墜鍔ㄨˉ鍗?");
        }
        order.setStatus(order.isPreAuthorization() ? DemoOrderStatus.FROZEN : DemoOrderStatus.COMPLETED);
        order.setSupplemented(true);
        ensureTradeNo(order, order.isPreAuthorization() ? "AUTH_SUPP" : "SUPP");
        markPaidTransition(order, DemoOrderStatus.UNPAID);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView refund(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (!isRefundableStatus(order.getStatus())) {
            throw new IllegalStateException("鍙湁宸插畬鎴愯鍗曞彲浠ラ€€娆?");
        }
        BigDecimal remaining = refundableAmount(order);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("璁㈠崟宸叉棤鍙€€閲戦");
        }
        if (databaseBacked()) {
            RefundCreateRequest request = new RefundCreateRequest(
                    order.getOutTradeNo(),
                    order.getTradeNo(),
                    remaining,
                    "LOCAL_RF_" + order.getOutTradeNo() + "_" + System.currentTimeMillis(),
                    "后台本地退款标记",
                    null,
                    order.getChannelId() == null ? List.of() : List.of(order.getChannelId()),
                    Map.of()
            );
            GatewayResponse response = new GatewayResponse(
                    order.getChannelId(),
                    PaymentStatus.SUCCESS,
                    "LOCAL",
                    "Local refund mark",
                    order.getOutTradeNo(),
                    order.getTradeNo(),
                    null,
                    null,
                    Map.of("local", true),
                    List.of()
            );
            recordRefundOrder(order, request, response, remaining);
            order.setRefundedAmount(successfulRefundAmount(order.getOutTradeNo()));
        } else {
            order.setRefundedAmount(money(order.getRefundedAmount().add(remaining)));
            memoryRefunds.put("LOCAL_RF_" + java.util.UUID.randomUUID(),
                    new MemoryRefund(order.getOutTradeNo(), remaining, PaymentStatus.SUCCESS, LocalDateTime.now()));
        }
        applyRefundStatus(order);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized void ensureRefundable(String outTradeNo, String tradeNo, BigDecimal refundAmount) {
        DemoOrder order = orderByIdentifierOrThrow(outTradeNo, tradeNo);
        BigDecimal amount = money(refundAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than 0");
        }
        if (!isRefundableStatus(order.getStatus())) {
            throw new IllegalStateException("Only completed or partially refunded orders can be refunded");
        }
        BigDecimal remaining = refundableAmount(order);
        if (amount.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Refund amount cannot exceed remaining refundable amount " + remaining.toPlainString());
        }
    }

    public synchronized BigDecimal amountByIdentifier(String outTradeNo, String tradeNo) {
        return money(orderByIdentifierOrThrow(outTradeNo, tradeNo).getAmount());
    }

    public synchronized DemoOrderView recordRefund(RefundCreateRequest request, GatewayResponse response) {
        if (databaseBacked()) {
            return refundTransaction().execute(status -> {
                DemoOrder original = orderByIdentifierOrThrow(request.outTradeNo(), request.tradeNo());
                jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                        String.class, original.getOutTradeNo());
                return recordRefundInternal(request, response);
            });
        }
        return recordRefundInternal(request, response);
    }

    private DemoOrderView recordRefundInternal(RefundCreateRequest request, GatewayResponse response) {
        DemoOrder order = orderByIdentifierOrThrow(request.outTradeNo(), request.tradeNo());
        BigDecimal refundAmount = money(request.refundAmount());
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than 0");
        }
        if (databaseBacked()) {
            recordRefundOrder(order, request, response, refundAmount);
            order.setRefundedAmount(successfulRefundAmount(order.getOutTradeNo()));
        } else {
            MemoryRefund previous = memoryRefunds.get(request.outRequestNo());
            if (previous != null && (!previous.outTradeNo().equals(order.getOutTradeNo())
                    || previous.amount().compareTo(refundAmount) != 0)) {
                throw refundConflict();
            }
            PaymentStatus next = switch (response.status()) {
                case SUCCESS -> PaymentStatus.SUCCESS;
                case FAILED, CLOSED -> PaymentStatus.FAILED;
                default -> PaymentStatus.PENDING;
            };
            // A delayed pending response cannot undo a confirmed refund, nor count it twice.
            if (previous != null && previous.status() == PaymentStatus.SUCCESS) {
                next = PaymentStatus.SUCCESS;
            } else if (next == PaymentStatus.SUCCESS) {
                order.setRefundedAmount(money(order.getRefundedAmount().add(refundAmount)));
            }
            LocalDateTime completedAt = previous == null ? null : previous.completedAt();
            if (next == PaymentStatus.SUCCESS && completedAt == null) completedAt = LocalDateTime.now();
            memoryRefunds.put(request.outRequestNo(), new MemoryRefund(order.getOutTradeNo(), refundAmount, next, completedAt));
        }
        if (hasText(response.tradeNo())) {
            order.setTradeNo(response.tradeNo().trim());
        }
        applyRefundStatus(order);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView recordDouyinProfitSharingNotify(String channelId, String transactionId) {
        if (!hasText(channelId) || !hasText(transactionId)) {
            throw new IllegalArgumentException("Douyin profit-sharing notification requires channelId and transactionId");
        }
        DemoOrder order = orderByIdentifierOrThrow(null, transactionId);
        if (!channelId.trim().equals(order.getChannelId())) {
            throw new IllegalArgumentException("Douyin profit-sharing notification channel does not match the payment order");
        }
        if (!order.isProfitShared()) {
            if (databaseBacked()) {
                jdbcTemplate.update("""
                        UPDATE pay_order SET profit_shared = 1
                        WHERE out_trade_no = ? AND channel_id = ? AND trade_no = ?
                        """, order.getOutTradeNo(), channelId.trim(), transactionId.trim());
            }
            order.setProfitShared(true);
        }
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView recordDouyinRefundNotify(
            String channelId,
            DouyinRefundEvidence evidence,
            String refundStatus
    ) {
        if (!databaseBacked()) {
            throw new IllegalStateException("Douyin refund notifications require database mode");
        }
        if (!hasText(channelId) || evidence == null || !hasText(evidence.outRefundNo())
                || (!hasText(evidence.outTradeNo()) && !hasText(evidence.transactionId()))
                || !hasText(evidence.refundId()) || evidence.refundAmount() == null
                || evidence.refundAmount().signum() <= 0) {
            throw new IllegalArgumentException("Douyin refund identity and amount are required");
        }
        return refundTransaction().execute(status -> recordDouyinRefundInternal(channelId, evidence, refundStatus));
    }

    private TransactionTemplate refundTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())));
        // MySQL defaults to REPEATABLE READ. An identifier lookup before waiting for the order lock
        // must not pin the refund aggregate to a snapshot from before another instance committed.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private DemoOrderView recordDouyinRefundInternal(String channelId, DouyinRefundEvidence evidence, String refundStatus) {
        String outRefundNo = evidence.outRefundNo();
        String outTradeNo;
        try {
            outTradeNo = jdbcTemplate.queryForObject(
                    "SELECT out_trade_no FROM refund_order WHERE out_request_no = ?",
                    String.class,
                    outRefundNo
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Refund order does not exist: " + outRefundNo);
        }
        // Match the reservation lock order: payment first, then refund. Other instances use the same DB lock.
        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                String.class, outTradeNo);
        Map<String, Object> refund = jdbcTemplate.queryForMap(
                "SELECT out_trade_no, channel_id, merchant_id, trade_no, refund_amount, code, message FROM refund_order WHERE out_request_no = ? FOR UPDATE",
                outRefundNo);
        DemoOrder order = order(outTradeNo);
        if (!channelId.equals(refund.get("channel_id")) || !channelId.equals(order.getChannelId())
                || !Objects.equals(refund.get("merchant_id"), order.getMerchantId())
                || !Objects.equals(refund.get("out_trade_no"), order.getOutTradeNo())
                || (hasText(evidence.outTradeNo()) && !evidence.outTradeNo().equals(order.getOutTradeNo()))
                || (hasText(evidence.transactionId()) && (!evidence.transactionId().equals(order.getTradeNo())
                    || (refund.get("trade_no") != null && !evidence.transactionId().equals(refund.get("trade_no")))))
                || evidence.refundAmount().compareTo(new BigDecimal(refund.get("refund_amount").toString())) != 0
                || (evidence.totalAmount() != null && evidence.totalAmount().compareTo(order.getAmount()) != 0)) {
            throw new IllegalArgumentException("Douyin refund evidence does not match the original order and refund reservation");
        }
        String rawResponse = jdbcTemplate.queryForObject("SELECT raw_response FROM refund_order WHERE out_request_no = ?",
                String.class, outRefundNo);
        ObjectNode storedResponse = refundResponse(rawResponse);
        String storedRefundId = storedRefundId(storedResponse, refund.get("code"), refund.get("message"));
        if (hasText(storedRefundId) && !evidence.refundId().equals(storedRefundId)) {
            throw new IllegalArgumentException("Douyin platform refund ID does not match the reserved refund");
        }
        storedResponse.put("verified_refund_id", evidence.refundId());
        String localStatus = normalizeDouyinRefundStatus(refundStatus);
        jdbcTemplate.update("""
                UPDATE refund_order
                SET code = CASE WHEN status = 'SUCCESS' THEN code ELSE ? END,
                    message = CASE WHEN status = 'SUCCESS' THEN message ELSE ? END,
                    raw_response = ?,
                    completed_at = CASE
                        WHEN status = 'SUCCESS' THEN completed_at
                        WHEN ? IN ('SUCCESS', 'FAILED') THEN CURRENT_TIMESTAMP
                        ELSE completed_at
                    END,
                    status = CASE
                        WHEN status = 'SUCCESS' THEN status
                        WHEN ? = 'PENDING' THEN status
                        ELSE ?
                    END
                WHERE out_request_no = ?
                """,
                nullIfBlank(evidence.refundId()),
                nullIfBlank(refundStatus),
                json(storedResponse),
                localStatus,
                localStatus,
                localStatus,
                outRefundNo
        );
        order.setRefundedAmount(successfulRefundAmount(outTradeNo));
        applyRefundStatus(order);
        persist(order);
        return DemoOrderView.from(order);
    }

    private static ObjectNode refundResponse(String value) {
        if (!hasText(value)) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(value);
            if (node instanceof ObjectNode object) return object;
            throw new IllegalArgumentException("Invalid stored refund response");
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid stored refund response", ex);
        }
    }

    private static String storedRefundId(ObjectNode response, Object code, Object message) {
        for (JsonNode value : List.of(response.path("verified_refund_id"), response.path("raw").path("refund_id"),
                response.path("raw").path("data").path("refund_id"))) {
            if (value.isTextual() && hasText(value.textValue())) return value.textValue();
        }
        // Earlier callback versions stored the platform refund ID in code, with the upstream state in message.
        if (code instanceof String id && hasText(id) && message instanceof String state
                && List.of("SUCCESS", "FAILED", "FAIL", "CLOSED", "PROCESSING", "PENDING", "ABNORMAL", "REFUNDED")
                .contains(state.toUpperCase(Locale.ROOT))
                && !List.of("SUCCESS", "FAILED", "FAIL", "CLOSED", "PROCESSING", "PENDING", "ABNORMAL", "REFUNDED", "0")
                .contains(id.toUpperCase(Locale.ROOT))) return id;
        return null;
    }

    /** Reserve the real capture identity and its collection order before contacting the provider. */
    public synchronized GatewayResponse reservePreauthCapture(PreauthCaptureRequest request, String channelId) {
        if (!hasText(request.preauthOutTradeNo()) || !hasText(request.outTradeNo())
                || request.preauthOutTradeNo().equals(request.outTradeNo())) {
            throw new IllegalArgumentException("Capture requires distinct parent and collection order numbers");
        }
        if (databaseBacked()) {
            return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .execute(status -> {
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                                String.class, request.preauthOutTradeNo());
                        return reserveCaptureInternal(request, channelId);
                    });
        }
        return reserveCaptureInternal(request, channelId);
    }

    private GatewayResponse reserveCaptureInternal(PreauthCaptureRequest request, String channelId) {
        BigDecimal amount = exactPositiveMoney(request.totalAmount());
        String mode = firstText(request.authConfirmMode(), "COMPLETE");
        if (!List.of("COMPLETE", "NOT_COMPLETE").contains(mode)) {
            throw new IllegalArgumentException("Unsupported authConfirmMode");
        }
        CaptureReservation existing = capture(request.outTradeNo());
        if (existing != null) {
            if (!existing.parent().equals(request.preauthOutTradeNo()) || !existing.channel().equals(channelId)
                    || !existing.authNo().equals(request.authNo()) || !existing.mode().equals(mode)
                    || existing.amount().compareTo(amount) != 0) {
                throw new IllegalArgumentException("Capture order number already belongs to a different request");
            }
            DemoOrder child = order(existing.child());
            return new GatewayResponse(existing.channel(), existing.status(), "CAPTURE_ALREADY_RESERVED",
                    "Capture already submitted; query its collection order instead of submitting again",
                    existing.child(), child.getTradeNo(), null, null, Map.of(), List.of());
        }
        DemoOrder parent = order(request.preauthOutTradeNo());
        if (!parent.isPreAuthorization() || parent.getStatus() != DemoOrderStatus.FROZEN
                || !Objects.equals(parent.getChannelId(), channelId)
                || !hasText(parent.getTradeNo()) || !parent.getTradeNo().equals(request.authNo())) {
            throw new IllegalArgumentException("Capture must match the frozen order, original channel and authorization number");
        }
        if (hasPendingCapture(parent.getOutTradeNo()) || hasPendingUnfreeze(parent.getOutTradeNo())) {
            throw new IllegalStateException("A capture is awaiting confirmation; reconcile it before another capture or unfreeze");
        }
        if (amount.compareTo(preauthUnfreezeRemainingAmount(parent)) > 0) {
            throw new IllegalArgumentException("Capture amount exceeds the remaining frozen balance");
        }
        ensureNewPaymentOrder(request.outTradeNo());
        DemoOrder child = new DemoOrder(request.outTradeNo(), null, channelId, parent.getMerchantId(),
                parent.getMerchantName(), "预授权实扣", request.subject(), amount, DemoOrderStatus.UNPAID,
                LocalDateTime.now().format(DISPLAY_TIME), false);
        CaptureReservation reservation = new CaptureReservation(request.outTradeNo(), parent.getOutTradeNo(), channelId,
                request.authNo(), amount, mode, PaymentStatus.PENDING, null);
        if (databaseBacked()) {
            insertOrder(child);
            jdbcTemplate.update("""
                    INSERT INTO preauth_capture_order
                    (capture_out_trade_no, parent_out_trade_no, channel_id, auth_no, amount, confirm_mode, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                    """, reservation.child(), reservation.parent(), reservation.channel(), reservation.authNo(), amount, mode);
        } else {
            orders.put(child.getOutTradeNo(), child);
            memoryCaptures.put(reservation.child(), reservation);
        }
        Optional<MerchantNotifyTarget> notifyTarget = merchantNotifyTarget(parent.getOutTradeNo());
        if (notifyTarget.isPresent()) {
            if (databaseBacked()) {
                jdbcTemplate.update("UPDATE pay_order SET notify_url = ? WHERE out_trade_no = ?",
                        notifyTarget.get().notifyUrl(), child.getOutTradeNo());
            } else {
                merchantNotifyTargets.put(child.getOutTradeNo(), notifyTarget.get());
            }
        }
        return null;
    }

    public synchronized String captureParentOutTradeNo(String captureOutTradeNo) {
        CaptureReservation reservation = capture(captureOutTradeNo);
        return reservation == null ? null : reservation.parent();
    }

    public synchronized DemoOrderView captureOrderView(String parentOutTradeNo, String captureOutTradeNo) {
        CaptureReservation reservation = capture(captureOutTradeNo);
        return reservation != null && reservation.parent().equals(parentOutTradeNo)
                ? DemoOrderView.from(order(captureOutTradeNo)) : null;
    }

    public synchronized DemoOrderView recordCaptureResult(String captureOutTradeNo, String tradeNo, String channelId,
                                                          PaymentStatus status, BigDecimal amount, boolean confirmed) {
        CaptureReservation reservation = capture(captureOutTradeNo);
        if (reservation == null) throw new IllegalArgumentException("Capture was not reserved");
        if (databaseBacked()) {
            return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .execute(transaction -> {
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                                String.class, reservation.parent());
                        return recordCaptureInternal(capture(captureOutTradeNo), tradeNo, channelId, status, amount, confirmed);
                    });
        }
        return recordCaptureInternal(reservation, tradeNo, channelId, status, amount, confirmed);
    }

    private DemoOrderView recordCaptureInternal(CaptureReservation reservation, String tradeNo, String channelId,
                                               PaymentStatus status, BigDecimal amount, boolean confirmed) {
        DemoOrder child = order(reservation.child());
        requirePaymentIdentity(child, tradeNo, channelId, amount, status == PaymentStatus.SUCCESS);
        if (status == PaymentStatus.SUCCESS && !confirmed) {
            throw new IllegalArgumentException("Capture success requires verified receipt evidence");
        }
        // A response arriving after the asynchronous success must never downgrade the reservation.
        if (reservation.status() == PaymentStatus.SUCCESS) return DemoOrderView.from(child);
        PaymentStatus next = status == PaymentStatus.SUCCESS ? PaymentStatus.SUCCESS
                : status == PaymentStatus.FAILED || status == PaymentStatus.CLOSED ? PaymentStatus.FAILED : PaymentStatus.PENDING;
        DemoOrderStatus previous = child.getStatus();
        if (hasText(tradeNo)) child.setTradeNo(tradeNo);
        child.setStatus(next == PaymentStatus.SUCCESS ? DemoOrderStatus.COMPLETED
                : next == PaymentStatus.FAILED ? DemoOrderStatus.CLOSED : DemoOrderStatus.UNPAID);
        CaptureReservation updated = new CaptureReservation(reservation.child(), reservation.parent(), reservation.channel(),
                reservation.authNo(), reservation.amount(), reservation.mode(), next, child.getTradeNo());
        if (databaseBacked()) {
            jdbcTemplate.update("""
                    UPDATE preauth_capture_order SET status = ?, trade_no = ?,
                        completed_at = CASE WHEN ? IN ('SUCCESS','FAILED') THEN COALESCE(completed_at, CURRENT_TIMESTAMP) ELSE completed_at END
                    WHERE capture_out_trade_no = ?
                    """, next.name(), nullIfBlank(child.getTradeNo()), next.name(), reservation.child());
        } else {
            memoryCaptures.put(updated.child(), updated);
        }
        markPaidTransition(child, previous);
        persist(child);
        if (next == PaymentStatus.SUCCESS) {
            DemoOrder parent = order(reservation.parent());
            parent.setPreauthCapturedAmount(capturedAmount(parent.getOutTradeNo()));
            if ("COMPLETE".equals(reservation.mode()) || preauthUnfreezeRemainingAmount(parent).signum() == 0) {
                parent.setStatus(DemoOrderStatus.UNFROZEN);
            }
            // Keep the original authorization number, frozen principal, and pre-authorization flag.
            persist(parent);
        }
        return receiptResult(child, previous, next == PaymentStatus.SUCCESS);
    }

    private record CaptureReservation(String child, String parent, String channel, String authNo,
                                      BigDecimal amount, String mode, PaymentStatus status, String tradeNo) { }

    private CaptureReservation capture(String child) {
        if (!hasText(child)) return null;
        if (!databaseBacked()) return memoryCaptures.get(child);
        List<CaptureReservation> rows = jdbcTemplate.query("SELECT * FROM preauth_capture_order WHERE capture_out_trade_no = ?",
                (rs, row) -> new CaptureReservation(rs.getString("capture_out_trade_no"), rs.getString("parent_out_trade_no"),
                        rs.getString("channel_id"), rs.getString("auth_no"), rs.getBigDecimal("amount"),
                        rs.getString("confirm_mode"), PaymentStatus.valueOf(rs.getString("status")), rs.getString("trade_no")), child);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean hasPendingCapture(String parent) {
        if (!databaseBacked()) return memoryCaptures.values().stream()
                .anyMatch(value -> value.parent().equals(parent) && value.status() == PaymentStatus.PENDING);
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preauth_capture_order WHERE parent_out_trade_no = ? AND status = 'PENDING'",
                Integer.class, parent) > 0;
    }

    private BigDecimal capturedAmount(String parent) {
        if (!databaseBacked()) return memoryCaptures.values().stream()
                .filter(value -> value.parent().equals(parent) && value.status() == PaymentStatus.SUCCESS)
                .map(CaptureReservation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return money(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM preauth_capture_order WHERE parent_out_trade_no = ? AND status = 'SUCCESS'",
                BigDecimal.class, parent));
    }

    private void ensurePreauthCaptureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS preauth_capture_order (
                  capture_out_trade_no VARCHAR(96) PRIMARY KEY,
                  parent_out_trade_no VARCHAR(96) NOT NULL,
                  channel_id VARCHAR(64) NOT NULL,
                  auth_no VARCHAR(128) NOT NULL,
                  amount DECIMAL(18,2) NOT NULL,
                  confirm_mode VARCHAR(32) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  trade_no VARCHAR(128),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  completed_at TIMESTAMP NULL
                )
                """);
    }

    private static BigDecimal exactPositiveMoney(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must be positive with at most two decimal places");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public synchronized void ensurePreauthUnfreezable(String outTradeNo, String authNo, BigDecimal amount) {
        if (!hasText(outTradeNo)) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        DemoOrder order = order(outTradeNo);
        if (hasPendingCapture(outTradeNo) || hasPendingUnfreeze(outTradeNo)) {
            throw new IllegalStateException("A capture is awaiting confirmation; reconcile it before unfreezing");
        }
        if (!order.isPreAuthorization()) {
            throw new IllegalStateException("只有预授权订单可以解冻");
        }
        if (order.getStatus() != DemoOrderStatus.FROZEN) {
            throw new IllegalStateException("只有冻结中的预授权订单可以解冻");
        }
        if (!hasText(authNo) || !authNo.equals(order.getTradeNo())) {
            throw new IllegalArgumentException("订单缺少预授权号，无法解冻");
        }
        BigDecimal unfreezeAmount = money(amount);
        if (unfreezeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("解冻金额必须大于 0");
        }
        BigDecimal remaining = preauthUnfreezeRemainingAmount(order);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("订单已无可解冻金额");
        }
        if (unfreezeAmount.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("解冻金额不能超过剩余可解冻金额");
        }
    }

    public synchronized DemoOrderView recordPreauthUnfreeze(PreauthUnfreezeRequest request, GatewayResponse response) {
        if (databaseBacked()) {
            return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .execute(status -> {
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                                String.class, request.preauthOutTradeNo());
                        return recordPreauthUnfreezeInternal(request, response);
                    });
        }
        return recordPreauthUnfreezeInternal(request, response);
    }

    private DemoOrderView recordPreauthUnfreezeInternal(PreauthUnfreezeRequest request, GatewayResponse response) {
        DemoOrder order = order(request.preauthOutTradeNo());
        BigDecimal unfreezeAmount = exactPositiveMoney(request.amount());
        UnfreezeReservation existing = unfreeze(request.outRequestNo());
        requireUnfreezeIdentity(existing, request, order.getChannelId());
        if (existing != null && existing.status() == PaymentStatus.SUCCESS) return DemoOrderView.from(order);
        if (hasPendingCapture(order.getOutTradeNo()) || !Objects.equals(response.channelId(), order.getChannelId())
                || response.status() != PaymentStatus.SUCCESS || !Objects.equals(request.authNo(), order.getTradeNo())
                || (hasText(response.tradeNo()) && !request.authNo().equals(response.tradeNo()))
                || unfreezeAmount.compareTo(preauthUnfreezeRemainingAmount(order)) > 0) {
            throw new IllegalArgumentException("Unfreeze result does not match the frozen order and balance");
        }
        if (databaseBacked()) {
            recordPreauthUnfreezeOrder(order, request, response, unfreezeAmount);
            order.setPreauthUnfrozenAmount(successfulPreauthUnfrozenAmount(order.getOutTradeNo()));
        } else {
            order.setPreauthUnfrozenAmount(money(order.getPreauthUnfrozenAmount().add(unfreezeAmount)));
            memoryUnfreezes.put(request.outRequestNo(), new UnfreezeReservation(order.getOutTradeNo(),
                    request.authNo(), order.getChannelId(), unfreezeAmount, PaymentStatus.SUCCESS));
        }
        if (preauthUnfreezeRemainingAmount(order).compareTo(BigDecimal.ZERO) <= 0) {
            order.setStatus(DemoOrderStatus.UNFROZEN);
            order.setPreAuthorization(true);
            order.setProductName("预授权已解冻");
        } else {
            order.setStatus(DemoOrderStatus.FROZEN);
            order.setPreAuthorization(true);
        }
        if (hasText(response.tradeNo())) {
            order.setTradeNo(response.tradeNo().trim());
        } else if (hasText(request.authNo())) {
            order.setTradeNo(request.authNo().trim());
        }
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized GatewayResponse reservePreauthUnfreeze(PreauthUnfreezeRequest request) {
        if (databaseBacked()) {
            return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .execute(status -> {
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                                String.class, request.preauthOutTradeNo());
                        return reserveUnfreezeInternal(request);
                    });
        }
        return reserveUnfreezeInternal(request);
    }

    private GatewayResponse reserveUnfreezeInternal(PreauthUnfreezeRequest request) {
        DemoOrder parent = order(request.preauthOutTradeNo());
        UnfreezeReservation existing = unfreeze(request.outRequestNo());
        requireUnfreezeIdentity(existing, request, parent.getChannelId());
        if (existing != null) {
            return new GatewayResponse(parent.getChannelId(), existing.status(), "UNFREEZE_ALREADY_RESERVED",
                    "Unfreeze already submitted; reconcile the original operation", parent.getOutTradeNo(), parent.getTradeNo(),
                    null, null, Map.of(), List.of());
        }
        ensurePreauthUnfreezable(parent.getOutTradeNo(), request.authNo(), request.amount());
        BigDecimal amount = exactPositiveMoney(request.amount());
        if (databaseBacked()) {
            jdbcTemplate.update("""
                    INSERT INTO preauth_unfreeze_order (out_request_no, out_trade_no, auth_no, channel_id, amount, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """, request.outRequestNo(), parent.getOutTradeNo(), request.authNo(), parent.getChannelId(), amount);
        } else {
            memoryUnfreezes.put(request.outRequestNo(), new UnfreezeReservation(parent.getOutTradeNo(), request.authNo(),
                    parent.getChannelId(), amount, PaymentStatus.PENDING));
        }
        return null;
    }

    private record UnfreezeReservation(String parent, String authNo, String channel, BigDecimal amount, PaymentStatus status) { }

    public synchronized void rejectPreauthUnfreeze(PreauthUnfreezeRequest request) {
        if (databaseBacked()) {
            jdbcTemplate.update("UPDATE preauth_unfreeze_order SET status = 'FAILED' WHERE out_request_no = ? AND status = 'PENDING'",
                    request.outRequestNo());
        } else {
            UnfreezeReservation existing = unfreeze(request.outRequestNo());
            if (existing != null && existing.status() == PaymentStatus.PENDING) {
                memoryUnfreezes.put(request.outRequestNo(), new UnfreezeReservation(existing.parent(), existing.authNo(),
                        existing.channel(), existing.amount(), PaymentStatus.FAILED));
            }
        }
    }

    private UnfreezeReservation unfreeze(String requestNo) {
        if (!databaseBacked()) return memoryUnfreezes.get(requestNo);
        List<UnfreezeReservation> rows = jdbcTemplate.query(
                "SELECT out_trade_no, auth_no, channel_id, amount, status FROM preauth_unfreeze_order WHERE out_request_no = ?",
                (rs, row) -> new UnfreezeReservation(rs.getString("out_trade_no"), rs.getString("auth_no"),
                        rs.getString("channel_id"), rs.getBigDecimal("amount"), PaymentStatus.valueOf(rs.getString("status"))), requestNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean hasPendingUnfreeze(String parent) {
        if (!databaseBacked()) return memoryUnfreezes.values().stream()
                .anyMatch(value -> value.parent().equals(parent) && value.status() == PaymentStatus.PENDING);
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preauth_unfreeze_order WHERE out_trade_no = ? AND status = 'PENDING'",
                Integer.class, parent) > 0;
    }

    private static void requireUnfreezeIdentity(UnfreezeReservation existing, PreauthUnfreezeRequest request, String channel) {
        if (existing != null && (!existing.parent().equals(request.preauthOutTradeNo())
                || !existing.authNo().equals(request.authNo()) || !existing.channel().equals(channel)
                || existing.amount().compareTo(request.amount()) != 0)) {
            throw new IllegalArgumentException("Unfreeze request number belongs to a different operation");
        }
    }

    public synchronized DemoOrderView delete(String outTradeNo) {
        if (databaseBacked()) {
            return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .execute(status -> {
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE", String.class, outTradeNo);
                        return deleteInternal(outTradeNo);
                    });
        }
        return deleteInternal(outTradeNo);
    }

    private DemoOrderView deleteInternal(String outTradeNo) {
        boolean linked = databaseBacked()
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preauth_capture_order WHERE parent_out_trade_no = ? OR capture_out_trade_no = ?",
                        Integer.class, outTradeNo, outTradeNo) > 0
                : memoryCaptures.values().stream().anyMatch(value -> value.parent().equals(outTradeNo) || value.child().equals(outTradeNo));
        if (linked || hasPendingUnfreeze(outTradeNo)) {
            throw new IllegalStateException("Orders with authorization operations must be retained for reconciliation");
        }
        DemoOrder removed = order(outTradeNo);
        if (databaseBacked()) {
            jdbcTemplate.update("DELETE FROM pay_order WHERE out_trade_no = ?", outTradeNo);
            return DemoOrderView.from(removed);
        }
        removed = orders.remove(outTradeNo);
        if (removed == null) {
            throw new IllegalArgumentException("璁㈠崟涓嶅瓨鍦? " + outTradeNo);
        }
        return DemoOrderView.from(removed);
    }

    public synchronized DemoOrderView recordPaymentCreated(
            String outTradeNo,
            String tradeNo,
            String channelId,
            String merchantId,
            String merchantName,
            String productName,
            BigDecimal amount,
            boolean preAuthorization,
            PaymentStatus paymentStatus
    ) {
        return recordPaymentCreated(
                outTradeNo,
                tradeNo,
                channelId,
                merchantId,
                merchantName,
                productName,
                productName,
                amount,
                preAuthorization,
                paymentStatus
        );
    }

    public synchronized DemoOrderView recordPaymentCreated(
            String outTradeNo,
            String tradeNo,
            String channelId,
            String merchantId,
            String merchantName,
            String productName,
            String subject,
            BigDecimal amount,
            boolean preAuthorization,
            PaymentStatus paymentStatus
    ) {
        return recordPaymentCreated(outTradeNo, tradeNo, channelId, merchantId, merchantName, productName,
                subject, amount, preAuthorization, paymentStatus, false);
    }

    public synchronized DemoOrderView recordPaymentCreated(
            String outTradeNo, String tradeNo, String channelId, String merchantId, String merchantName,
            String productName, String subject, BigDecimal amount, boolean preAuthorization,
            PaymentStatus paymentStatus, boolean confirmedReceipt
    ) {
        DemoOrderStatus initialStatus = statusFromGateway(paymentStatus, preAuthorization, DemoOrderStatus.UNPAID);
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        DemoOrderStatus previousStatus = order == null ? null : order.getStatus();
        boolean matchesReceipt = order == null || receiptIdentityMatches(order, tradeNo, channelId, amount);
        if (order == null) {
            order = new DemoOrder(
                    outTradeNo,
                    tradeNo,
                    channelId,
                    merchantId,
                    merchantName,
                    productName,
                    firstText(subject, productName),
                    amount,
                    initialStatus,
                    LocalDateTime.now().format(DISPLAY_TIME),
                    preAuthorization
            );
            seed(order);
        } else {
            if (hasText(tradeNo)) {
                order.setTradeNo(tradeNo.trim());
            }
            order.setSubject(firstText(subject, order.getSubject(), productName));
            order.setStatus(preserveCollectedStatus(order.getStatus(),
                    statusFromGateway(paymentStatus, preAuthorization, order.getStatus())));
        }
        markPaidTransition(order, previousStatus);
        persist(order);
        return receiptResult(order, previousStatus, confirmedReceipt && matchesReceipt
                && paymentStatus == PaymentStatus.SUCCESS && !preAuthorization);
    }

    public synchronized void recordPaymentMetadata(
            String outTradeNo,
            PayCreateRequest request,
            GatewayResponse response
    ) {
        if (!hasText(outTradeNo) || request == null) {
            return;
        }
        if (!databaseBacked()) {
            DemoOrder order = orders.get(outTradeNo);
            if (order != null && hasText(request.notifyUrl())) {
                merchantNotifyTargets.put(outTradeNo, new MerchantNotifyTarget(order.getMerchantId(), request.notifyUrl().trim()));
            } else {
                merchantNotifyTargets.remove(outTradeNo);
            }
            return;
        }
        jdbcTemplate.update("""
                UPDATE pay_order
                SET buyer_id = ?, buyer_open_id = ?, auth_code = ?, notify_url = ?, return_url = ?,
                    app_auth_token = ?, settle_info = ?, royalty_info = ?, extra = ?,
                    raw_request = ?, raw_response = ?
                WHERE out_trade_no = ?
                """,
                nullIfBlank(request.buyerId()),
                nullIfBlank(request.buyerOpenId()),
                nullIfBlank(request.authCode()),
                nullIfBlank(request.notifyUrl()),
                nullIfBlank(request.returnUrl()),
                nullIfBlank(request.appAuthToken()),
                json(request.settleInfo()),
                json(request.royaltyInfo()),
                json(request.extra()),
                json(request),
                json(response),
                outTradeNo
        );
    }

    public synchronized DemoOrderView recordPaymentResult(
            String outTradeNo,
            String tradeNo,
            String channelId,
            PaymentStatus paymentStatus
    ) {
        return recordPaymentResult(outTradeNo, tradeNo, channelId, paymentStatus, false, null);
    }

    public synchronized DemoOrderView recordPaymentResult(String outTradeNo, String tradeNo, String channelId,
                                                          PaymentStatus paymentStatus, boolean confirmedReceipt,
                                                          BigDecimal confirmedAmount) {
        if (capture(outTradeNo) != null) {
            return recordCaptureResult(outTradeNo, tradeNo, channelId, paymentStatus, confirmedAmount, confirmedReceipt);
        }
        return withPaymentOrderLock(outTradeNo, () -> recordPaymentResultInternal(outTradeNo, tradeNo, channelId,
                paymentStatus, confirmedReceipt, confirmedAmount));
    }

    private DemoOrderView recordPaymentResultInternal(String outTradeNo, String tradeNo, String channelId,
                                                      PaymentStatus paymentStatus, boolean confirmedReceipt, BigDecimal confirmedAmount) {
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) {
            throw new IllegalArgumentException("Order does not exist: " + outTradeNo);
        }
        requirePaymentIdentity(order, tradeNo, channelId, confirmedAmount, paymentStatus == PaymentStatus.SUCCESS);
        if (paymentStatus == PaymentStatus.SUCCESS && !confirmedReceipt) {
            throw new IllegalArgumentException("Payment success requires verified receipt evidence");
        }
        DemoOrderStatus previousStatus = order.getStatus();
        boolean matchesReceipt = receiptIdentityMatches(order, tradeNo, channelId, confirmedAmount);
        if (hasText(tradeNo)) {
            order.setTradeNo(tradeNo.trim());
        }
        order.setStatus(preserveCollectedStatus(
                order.getStatus(),
                statusFromGateway(paymentStatus, order.isPreAuthorization(), order.getStatus())
        ));
        markPaidTransition(order, previousStatus);
        persist(order);
        return receiptResult(order, previousStatus, confirmedReceipt && matchesReceipt
                && paymentStatus == PaymentStatus.SUCCESS);
    }

    public synchronized DemoOrderView recordPreauthAuthNo(String outTradeNo, String authNo, String channelId) {
        DemoOrder order = order(outTradeNo);
        if (hasText(authNo)) {
            order.setTradeNo(authNo.trim());
        }
        if (hasText(channelId)) {
            order.setChannelId(channelId.trim());
        }
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized String paymentRequestProductCode(String outTradeNo) {
        if (!databaseBacked() || !hasText(outTradeNo)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT COALESCE(
                        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(raw_response, '$.raw.request_product_code')), 'null'),
                        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(raw_request, '$.extra.product_code')), 'null')
                    )
                    FROM pay_order
                    WHERE out_trade_no = ?
                    """, String.class, outTradeNo);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    public synchronized void ensureMerchantOrder(String merchantId, String outTradeNo, String tradeNo) {
        resolveMerchantOrder(merchantId, outTradeNo, tradeNo);
    }

    public synchronized DemoOrderView resolveMerchantOrder(String merchantId, String outTradeNo, String tradeNo) {
        if (!hasText(merchantId) || (!hasText(outTradeNo) && !hasText(tradeNo))) {
            throw new MerchantApiException("INVALID_ORDER_ID", "merchantId and at least one of outTradeNo or tradeNo are required");
        }
        // Resolve tradeNo inside the merchant namespace; different channels may reuse upstream IDs.
        List<DemoOrderView> candidates;
        if (hasText(outTradeNo)) {
            DemoOrder found = databaseBacked() ? findOrder(outTradeNo.trim()) : orders.get(outTradeNo.trim());
            candidates = found == null ? List.of() : List.of(DemoOrderView.from(found));
        } else {
            candidates = byMerchant(merchantId).stream().filter(order -> tradeNo.trim().equals(order.tradeNo())).toList();
        }
        if (candidates.size() != 1 || !merchantId.equals(candidates.getFirst().merchantId())) {
            throw new MerchantApiException("ORDER_NOT_FOUND", "Order is unavailable for this merchant; use outTradeNo if tradeNo is ambiguous");
        }
        DemoOrderView order = candidates.getFirst();
        if (hasText(tradeNo) && !tradeNo.trim().equals(order.tradeNo())) {
            throw new MerchantApiException("ORDER_IDENTIFIER_MISMATCH", "outTradeNo and tradeNo must identify the same order");
        }
        return order;
    }

    public synchronized DemoOrderView reserveMerchantPayment(String merchantId, String merchantName,
                                                              String channelId, PayCreateRequest request) {
        DemoOrder order = new DemoOrder(request.outTradeNo(), null, channelId, merchantId, merchantName,
                request.product().label(), request.subject(), request.totalAmount(), DemoOrderStatus.UNPAID,
                LocalDateTime.now().format(DISPLAY_TIME), request.product() == PaymentProduct.ALIPAY_PREAUTH
                || request.product() == PaymentProduct.ALIPAY_PREAUTH_H5);
        if (databaseBacked()) {
            try {
                // INSERT (never upsert) lets the primary key arbitrate across server instances.
                insertOrder(order);
            } catch (DuplicateKeyException ex) {
                throw orderConflict();
            }
        } else {
            if (orders.containsKey(request.outTradeNo())) {
                throw orderConflict();
            }
            seed(order);
        }
        recordPaymentMetadata(request.outTradeNo(), request, null);
        return DemoOrderView.from(order);
    }

    public synchronized void ensureNewPaymentOrder(String outTradeNo) {
        if (databaseBacked() ? exists(outTradeNo) : orders.containsKey(outTradeNo)) {
            throw orderConflict();
        }
    }

    private static MerchantApiException orderConflict() {
        return new MerchantApiException("ORDER_CONFLICT", "outTradeNo already exists or is processing; query the original order before any further payment attempt");
    }

    public synchronized Optional<MerchantNotifyTarget> merchantNotifyTarget(String outTradeNo) {
        if (!hasText(outTradeNo)) {
            return Optional.empty();
        }
        if (!databaseBacked()) {
            return Optional.ofNullable(merchantNotifyTargets.get(outTradeNo));
        }
        return jdbcTemplate.query("SELECT merchant_id, notify_url FROM pay_order WHERE out_trade_no = ?", rs -> {
            if (!rs.next() || !hasText(rs.getString("notify_url"))) {
                return Optional.empty();
            }
            return Optional.of(new MerchantNotifyTarget(rs.getString("merchant_id"), rs.getString("notify_url").trim()));
        }, outTradeNo);
    }

    public record MerchantNotifyTarget(String merchantId, String notifyUrl) { }

    public synchronized void reserveMerchantRefund(String merchantId, RefundCreateRequest request) {
        if (!hasText(request.outRequestNo()) || !request.outRequestNo().equals(request.outRequestNo().trim())) {
            throw new MerchantApiException("INVALID_REFUND_ID", "outRequestNo must be nonblank without surrounding whitespace");
        }
        if (request.refundAmount() == null || request.refundAmount().signum() <= 0
                || request.refundAmount().stripTrailingZeros().scale() > 2) {
            throw new MerchantApiException("INVALID_AMOUNT", "refundAmount must be positive with at most two decimal places");
        }
        if (databaseBacked()) {
            new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                    .executeWithoutResult(status -> {
                        // Lock the original order while checking pending refunds and reserving this request.
                        jdbcTemplate.queryForObject("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE",
                                String.class, request.outTradeNo());
                        reserveMerchantRefundInternal(merchantId, request);
                    });
        } else {
            reserveMerchantRefundInternal(merchantId, request);
        }
    }

    private void reserveMerchantRefundInternal(String merchantId, RefundCreateRequest request) {
        DemoOrderView order = resolveMerchantOrder(merchantId, request.outTradeNo(), request.tradeNo());
        if (databaseBacked()) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_order WHERE out_request_no = ?",
                    Integer.class, request.outRequestNo());
            if (count != null && count > 0) {
                throw refundConflict();
            }
        } else if (memoryRefunds.containsKey(request.outRequestNo())) {
            throw refundConflict();
        }
        ensureRefundable(order.outTradeNo(), order.tradeNo(), request.refundAmount());
        BigDecimal pending = databaseBacked() ? jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM refund_order WHERE out_trade_no = ? AND status = 'PENDING'",
                BigDecimal.class, order.outTradeNo()) : memoryRefunds.values().stream()
                .filter(refund -> refund.outTradeNo().equals(order.outTradeNo()) && refund.status() == PaymentStatus.PENDING)
                .map(MemoryRefund::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (request.refundAmount().add(money(pending)).compareTo(order.refundableAmount()) > 0) {
            throw new MerchantApiException("REFUND_PENDING", "Refund amount exceeds the balance after pending refunds; reconcile pending requests first");
        }
        if (databaseBacked()) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO refund_order (out_request_no, out_trade_no, trade_no, merchant_id, channel_id,
                                                  refund_amount, status, refund_reason)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                        """, request.outRequestNo(), order.outTradeNo(), order.tradeNo(), merchantId,
                        order.channelId(), request.refundAmount(), nullIfBlank(request.refundReason()));
            } catch (DuplicateKeyException ex) {
                throw refundConflict();
            }
        } else {
            memoryRefunds.put(request.outRequestNo(), new MemoryRefund(order.outTradeNo(), request.refundAmount(), PaymentStatus.PENDING, null));
        }
    }

    private static MerchantApiException refundConflict() {
        return new MerchantApiException("REFUND_CONFLICT", "outRequestNo already exists or is processing; reconcile the original refund instead of submitting it again");
    }

    private record MemoryRefund(String outTradeNo, BigDecimal amount, PaymentStatus status, LocalDateTime completedAt) { }

    public record RefundReceipt(String outRequestNo, String outTradeNo, BigDecimal amount, LocalDateTime completedAt) { }

    /** Immutable event snapshots for in-memory reporting; a replay retains the first completion time. */
    public synchronized List<RefundReceipt> successfulRefundReceipts() {
        return memoryRefunds.entrySet().stream().filter(entry -> entry.getValue().status() == PaymentStatus.SUCCESS)
                .map(entry -> new RefundReceipt(entry.getKey(), entry.getValue().outTradeNo(),
                        entry.getValue().amount(), entry.getValue().completedAt())).toList();
    }

    public record PaymentReceipt(String outTradeNo, String merchantId, BigDecimal amount, LocalDateTime paidAt) { }

    public synchronized List<PaymentReceipt> successfulPaymentReceipts() {
        return orders.values().stream().filter(order -> !order.isPreAuthorization()
                        && (order.getStatus() == DemoOrderStatus.COMPLETED || isRefundStatus(order.getStatus())))
                .map(order -> new PaymentReceipt(order.getOutTradeNo(), order.getMerchantId(), order.getAmount(), order.getPaidAt()))
                .toList();
    }

    public synchronized DemoOrderView recordAlipayNotify(
            String outTradeNo,
            String tradeNo,
            String channelId,
            BigDecimal amount,
            String tradeStatus
    ) {
        return recordAlipayNotify(outTradeNo, tradeNo, channelId, amount, tradeStatus, false);
    }

    public synchronized DemoOrderView recordAlipayNotify(String outTradeNo, String tradeNo, String channelId,
                                                         BigDecimal amount, String tradeStatus,
                                                         boolean confirmedReceipt) {
        DemoOrderView capturedPreauth = recordPreauthCaptureNotify(outTradeNo, tradeNo, channelId, amount,
                tradeStatus, confirmedReceipt);
        if (capturedPreauth != null) {
            return capturedPreauth;
        }
        return withPaymentOrderLock(outTradeNo, () -> recordAlipayNotifyInternal(outTradeNo, tradeNo, channelId,
                amount, tradeStatus, confirmedReceipt));
    }

    private DemoOrderView recordAlipayNotifyInternal(String outTradeNo, String tradeNo, String channelId,
                                                    BigDecimal amount, String tradeStatus, boolean confirmedReceipt) {
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) throw new IllegalArgumentException("Notification order does not exist: " + outTradeNo);
        requirePaymentIdentity(order, tradeNo, channelId, amount, true);
        DemoOrderStatus previousStatus = order == null ? null : order.getStatus();
        boolean matchesReceipt = order != null && receiptIdentityMatches(order, tradeNo, channelId, amount);
        if (order == null) {
            order = new DemoOrder(
                    outTradeNo,
                    tradeNo,
                    channelId,
                    "M10001",
                    "榛樿鍟嗘埛",
                    "鏀粯瀹濇敮浠?",
                    "鏀粯瀹濇敮浠?",
                    amount == null ? BigDecimal.ZERO : amount,
                    statusFromAlipay(tradeStatus, false),
                    LocalDateTime.now().format(DISPLAY_TIME),
                    false
            );
            seed(order);
        } else {
            if (hasText(tradeNo)) {
                order.setTradeNo(tradeNo.trim());
            }
            DemoOrderStatus next = statusFromAlipay(tradeStatus, order.isPreAuthorization());
            // Stale waiting/closed callbacks must not turn a historical receipt into a new success transition.
            order.setStatus(preserveCollectedStatus(order.getStatus(), next));
        }
        markPaidTransition(order, previousStatus);
        persist(order);
        return receiptResult(order, previousStatus, confirmedReceipt && matchesReceipt
                && ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)));
    }

    private <T> T withPaymentOrderLock(String outTradeNo, java.util.function.Supplier<T> action) {
        if (!databaseBacked()) return action.get();
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbcTemplate.getDataSource())))
                .execute(status -> {
                    List<String> rows = jdbcTemplate.queryForList(
                            "SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE", String.class, outTradeNo);
                    if (rows.isEmpty()) throw new IllegalArgumentException("Order does not exist: " + outTradeNo);
                    return action.get();
                });
    }

    private DemoOrderView recordPreauthCaptureNotify(String outTradeNo, String tradeNo, String channelId,
                                                     BigDecimal amount, String tradeStatus, boolean confirmedReceipt) {
        CaptureReservation reservation = capture(outTradeNo);
        if (reservation == null) return null;
        PaymentStatus status = switch (tradeStatus == null ? "" : tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PaymentStatus.SUCCESS;
            case "TRADE_CLOSED" -> PaymentStatus.CLOSED;
            default -> PaymentStatus.PENDING;
        };
        return recordCaptureResult(outTradeNo, tradeNo, channelId, status, amount, confirmedReceipt);
    }

    private static void requirePaymentIdentity(DemoOrder order, String tradeNo, String channelId,
                                               BigDecimal amount, boolean requireAmount) {
        if (!hasText(channelId) || !channelId.equals(order.getChannelId())
                || (hasText(tradeNo) && hasText(order.getTradeNo()) && !tradeNo.equals(order.getTradeNo()))
                || (requireAmount && (!hasText(tradeNo) || amount == null))
                || (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                    || order.getAmount().compareTo(amount) != 0))) {
            throw new IllegalArgumentException("Payment notification/query identity or amount does not match the original order");
        }
    }

    private static boolean receiptIdentityMatches(DemoOrder order, String tradeNo, String channelId,
                                                   BigDecimal amount) {
        return hasText(channelId) && channelId.equals(order.getChannelId()) && hasText(tradeNo)
                && (!hasText(order.getTradeNo()) || tradeNo.equals(order.getTradeNo()))
                && order.getAmount() != null && order.getAmount().signum() > 0
                && (amount == null || order.getAmount().compareTo(amount) == 0);
    }

    private DemoOrderView receiptResult(DemoOrder order, DemoOrderStatus previousStatus, boolean confirmedReceipt) {
        return receiptResult(order, previousStatus, confirmedReceipt, false, null);
    }

    private DemoOrderView receiptResult(DemoOrder order, DemoOrderStatus previousStatus, boolean confirmedReceipt,
                                       boolean rememberedCapture, BigDecimal capturedAmount) {
        DemoOrderView view = DemoOrderView.from(order);
        if (!confirmedReceipt || confirmedReceiptSink == null || order.isPreAuthorization()
                || order.isSupplemented() || order.getStatus() != DemoOrderStatus.COMPLETED
                || (previousStatus == DemoOrderStatus.COMPLETED && !rememberedCapture)
                || previousStatus == DemoOrderStatus.PARTIALLY_REFUNDED
                || previousStatus == DemoOrderStatus.REFUNDED || !hasText(order.getTradeNo())
                || !hasText(order.getChannelId()) || order.getAmount() == null || order.getAmount().signum() <= 0) {
            return view;
        }
        try {
            DemoOrderView receipt = view;
            if (capturedAmount != null) {
                // A partial capture is a receipt for the amount actually collected, not the frozen total.
                DemoOrder collected = new DemoOrder(order.getOutTradeNo(), order.getTradeNo(), order.getChannelId(),
                        order.getMerchantId(), order.getMerchantName(), order.getProductName(), order.getSubject(),
                        capturedAmount, order.getStatus(), order.getCreatedAt(), false);
                receipt = DemoOrderView.from(collected);
            }
            confirmedReceiptSink.enqueueConfirmedReceipt(receipt);
        } catch (Exception ex) {
            // Receipt delivery must not change a successful payment or its upstream acknowledgement.
            log.warn("Receipt notification could not be queued: outTradeNo={}, reason={}",
                    order.getOutTradeNo(), ex.getClass().getSimpleName());
        }
        return view;
    }

    private boolean databaseBacked() {
        return jdbcTemplate != null;
    }

    private DemoOrder mapOrder(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        DemoOrder order = new DemoOrder(
                rs.getString("out_trade_no"),
                rs.getString("trade_no"),
                rs.getString("channel_id"),
                rs.getString("merchant_id"),
                rs.getString("merchant_name"),
                firstText(rs.getString("product"), rs.getString("subject")),
                rs.getString("subject"),
                rs.getBigDecimal("amount"),
                status(rs.getString("status")),
                timestampText(rs.getTimestamp("created_at")),
                rs.getBoolean("pre_authorization")
        );
        order.setSupplemented(rs.getBoolean("supplemented"));
        order.setProfitShared(rs.getBoolean("profit_shared"));
        order.setRefundedAmount(rs.getBigDecimal("refunded_amount"));
        order.setPreauthUnfrozenAmount(rs.getBigDecimal("preauth_unfrozen_amount"));
        Timestamp paidAt = rs.getTimestamp("paid_at");
        order.setPaidAt(paidAt == null ? null : paidAt.toLocalDateTime());
        if (order.isPreAuthorization()) order.setPreauthCapturedAmount(capturedAmount(order.getOutTradeNo()));
        return order;
    }

    private void ensurePreauthUnfreezeTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS preauth_unfreeze_order (
                      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      out_request_no VARCHAR(96) NOT NULL,
                      out_trade_no VARCHAR(96) NOT NULL,
                      auth_no VARCHAR(128) NOT NULL,
                      channel_id VARCHAR(64) NULL,
                      amount DECIMAL(18,2) NOT NULL,
                      status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                      remark VARCHAR(255) NULL,
                      code VARCHAR(64) NULL,
                      message VARCHAR(512) NULL,
                      raw_response JSON NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      completed_at DATETIME NULL,
                      updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      UNIQUE KEY uk_preauth_unfreeze_request (out_request_no),
                      KEY idx_preauth_unfreeze_order (out_trade_no),
                      KEY idx_preauth_unfreeze_channel_time (channel_id, created_at),
                      CONSTRAINT fk_preauth_unfreeze_order
                        FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no)
                        ON DELETE CASCADE,
                      CONSTRAINT fk_preauth_unfreeze_channel
                        FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
                        ON DELETE SET NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预授权解冻记录'
                    """);
        } catch (DataAccessException ignored) {
            // The startup path should stay available; explicit unfreeze writes will surface DB permission issues.
        }
    }

    private DemoOrder findOrder(String outTradeNo) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                           COALESCE(m.name, o.merchant_id) AS merchant_name,
                           o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                           o.pre_authorization, o.supplemented, o.profit_shared,
                           COALESCE((
                               SELECT SUM(r.refund_amount)
                               FROM refund_order r
                               WHERE r.out_trade_no = o.out_trade_no
                                 AND r.status = 'SUCCESS'
                           ), 0) AS refunded_amount,
                           COALESCE((
                               SELECT SUM(u.amount)
                               FROM preauth_unfreeze_order u
                               WHERE u.out_trade_no = o.out_trade_no
                                 AND u.status = 'SUCCESS'
                           ), 0) AS preauth_unfrozen_amount
                    FROM pay_order o
                    LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                    WHERE o.out_trade_no = ?
                    """, this::mapOrder, outTradeNo);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private DemoOrder findOrderByIdentifier(String outTradeNo, String tradeNo) {
        if (hasText(outTradeNo)) {
            return findOrder(outTradeNo.trim());
        }
        if (!hasText(tradeNo)) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                           COALESCE(m.name, o.merchant_id) AS merchant_name,
                           o.product, o.subject, o.amount, o.status, o.created_at, o.paid_at,
                           o.pre_authorization, o.supplemented, o.profit_shared,
                           COALESCE((
                               SELECT SUM(r.refund_amount)
                               FROM refund_order r
                               WHERE r.out_trade_no = o.out_trade_no
                                 AND r.status = 'SUCCESS'
                           ), 0) AS refunded_amount,
                           COALESCE((
                               SELECT SUM(u.amount)
                               FROM preauth_unfreeze_order u
                               WHERE u.out_trade_no = o.out_trade_no
                                 AND u.status = 'SUCCESS'
                           ), 0) AS preauth_unfrozen_amount
                    FROM pay_order o
                    LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                    WHERE o.trade_no = ?
                    """, this::mapOrder, tradeNo.trim());
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    private DemoOrder memoryOrderByIdentifier(String outTradeNo, String tradeNo) {
        if (hasText(outTradeNo)) {
            return orders.get(outTradeNo.trim());
        }
        if (!hasText(tradeNo)) {
            return null;
        }
        return orders.values().stream()
                .filter(order -> tradeNo.trim().equals(order.getTradeNo()))
                .findFirst()
                .orElse(null);
    }

    private DemoOrder orderByIdentifierOrThrow(String outTradeNo, String tradeNo) {
        DemoOrder order = databaseBacked()
                ? findOrderByIdentifier(outTradeNo, tradeNo)
                : memoryOrderByIdentifier(outTradeNo, tradeNo);
        if (order == null) {
            throw new IllegalArgumentException("Order does not exist: " + firstText(outTradeNo, tradeNo));
        }
        return order;
    }

    private DemoOrder order(String outTradeNo) {
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) {
            throw new IllegalArgumentException("璁㈠崟涓嶅瓨鍦? " + outTradeNo);
        }
        return order;
    }

    private void persist(DemoOrder order) {
        if (databaseBacked()) {
            if (exists(order.getOutTradeNo())) {
                updateOrder(order);
            } else {
                insertOrder(order);
            }
        }
    }

    private static void markPaidTransition(DemoOrder order, DemoOrderStatus previous) {
        if (!order.isPreAuthorization() && order.getStatus() == DemoOrderStatus.COMPLETED && order.getPaidAt() == null
                && previous != DemoOrderStatus.COMPLETED && !isRefundStatus(previous)) {
            order.setPaidAt(LocalDateTime.now());
        }
    }

    private boolean exists(String outTradeNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pay_order WHERE out_trade_no = ?",
                Integer.class,
                outTradeNo
        );
        return count != null && count > 0;
    }

    private void insertOrder(DemoOrder order) {
        ensureMerchant(order.getMerchantId(), order.getMerchantName());
        jdbcTemplate.update("""
                INSERT INTO pay_order (
                    out_trade_no, trade_no, merchant_id, channel_id, product, subject, amount,
                    status, pre_authorization, supplemented, profit_shared, created_at, paid_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.getOutTradeNo(),
                nullIfBlank(order.getTradeNo()),
                order.getMerchantId(),
                existingChannelId(order.getChannelId()),
                firstText(order.getProductName(), "PAY"),
                firstText(order.getSubject(), order.getProductName(), "PAY"),
                order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                order.getStatus().name(),
                order.isPreAuthorization() ? 1 : 0,
                order.isSupplemented() ? 1 : 0,
                order.isProfitShared() ? 1 : 0,
                timestamp(order.getCreatedAt()),
                order.getPaidAt() == null ? null : Timestamp.valueOf(order.getPaidAt())
        );
    }

    private void updateOrder(DemoOrder order) {
        ensureMerchant(order.getMerchantId(), order.getMerchantName());
        jdbcTemplate.update("""
                UPDATE pay_order
                SET trade_no = ?, merchant_id = ?, channel_id = ?, product = ?, subject = ?,
                    amount = ?, status = ?, pre_authorization = ?, supplemented = ?, profit_shared = ?,
                    paid_at = COALESCE(paid_at, ?),
                    frozen_at = CASE WHEN ? = 'FROZEN' THEN COALESCE(frozen_at, CURRENT_TIMESTAMP) ELSE frozen_at END,
                    refunded_at = CASE WHEN ? IN ('PARTIALLY_REFUNDED', 'REFUNDED') THEN COALESCE(refunded_at, CURRENT_TIMESTAMP) ELSE refunded_at END,
                    closed_at = CASE WHEN ? IN ('CLOSED', 'UNFROZEN') THEN COALESCE(closed_at, CURRENT_TIMESTAMP) ELSE closed_at END
                WHERE out_trade_no = ?
                """,
                nullIfBlank(order.getTradeNo()),
                order.getMerchantId(),
                existingChannelId(order.getChannelId()),
                firstText(order.getProductName(), "PAY"),
                firstText(order.getSubject(), order.getProductName(), "PAY"),
                order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                order.getStatus().name(),
                order.isPreAuthorization() ? 1 : 0,
                order.isSupplemented() ? 1 : 0,
                order.isProfitShared() ? 1 : 0,
                order.getPaidAt() == null ? null : Timestamp.valueOf(order.getPaidAt()),
                order.getStatus().name(),
                order.getStatus().name(),
                order.getStatus().name(),
                order.getOutTradeNo()
        );
    }

    private void ensureMerchant(String merchantId, String merchantName) {
        if (!hasText(merchantId)) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO merchant (
                    merchant_id, name, fee_rate, status, settlement_status, md5_key, sign_mode, routing_mode
                ) VALUES (?, ?, 0.6000, '正常', '待结算', ?, 'MD5_RSA2', 'ROUND_ROBIN')
                """,
                merchantId,
                firstText(merchantName, merchantId),
                "MD5_AUTO_" + merchantId
        );
    }

    private String existingChannelId(String channelId) {
        if (!hasText(channelId)) {
            return null;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pay_channel WHERE id = ?",
                Integer.class,
                channelId
        );
        return count != null && count > 0 ? channelId : null;
    }

    private void seed(DemoOrder order) {
        orders.put(order.getOutTradeNo(), order);
    }

    private static void ensureTradeNo(DemoOrder order, String prefix) {
        if (order.getTradeNo() == null || order.getTradeNo().isBlank()) {
            order.setTradeNo(prefix + LocalDateTime.now().format(SERIAL_TIME));
        }
    }

    private static DemoOrderStatus statusFromAlipay(String tradeStatus, boolean preAuthorization) {
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            return preAuthorization ? DemoOrderStatus.FROZEN : DemoOrderStatus.COMPLETED;
        }
        if ("TRADE_CLOSED".equals(tradeStatus)) {
            return DemoOrderStatus.CLOSED;
        }
        return DemoOrderStatus.UNPAID;
    }

    private static DemoOrderStatus statusFromGateway(
            PaymentStatus paymentStatus,
            boolean preAuthorization,
            DemoOrderStatus currentStatus
    ) {
        if (paymentStatus == null) {
            return firstStatus(currentStatus);
        }
        return switch (paymentStatus) {
            case SUCCESS -> preAuthorization ? DemoOrderStatus.FROZEN : DemoOrderStatus.COMPLETED;
            case CLOSED -> DemoOrderStatus.CLOSED;
            case CREATED, PAYING, PENDING, UNKNOWN, FAILED -> firstStatus(currentStatus);
        };
    }

    private static DemoOrderStatus firstStatus(DemoOrderStatus status) {
        return status == null ? DemoOrderStatus.UNPAID : status;
    }

    private static DemoOrderStatus status(String value) {
        if (!hasText(value)) {
            return DemoOrderStatus.UNPAID;
        }
        try {
            return DemoOrderStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return DemoOrderStatus.UNPAID;
        }
    }

    private static String timestampText(Timestamp timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp.toLocalDateTime().format(DISPLAY_TIME);
    }

    private static Timestamp timestamp(String value) {
        if (!hasText(value)) {
            return Timestamp.valueOf(LocalDateTime.now());
        }
        return Timestamp.valueOf(LocalDateTime.parse(normalizeTime(value), DISPLAY_TIME));
    }

    private static boolean contains(String value, String needle) {
        return !hasText(needle) || (value != null && value.contains(needle.trim()));
    }

    private static int compareTime(String left, String right) {
        return normalizeTime(left).compareTo(normalizeTime(right));
    }

    private static String normalizeTime(String value) {
        String normalized = value == null ? "" : value.trim().replace('T', ' ');
        return normalized.length() == 16 ? normalized + ":00" : normalized;
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String nullIfBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private void recordRefundOrder(
            DemoOrder order,
            RefundCreateRequest request,
            GatewayResponse response,
            BigDecimal refundAmount
    ) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT out_trade_no, merchant_id, refund_amount, code, message FROM refund_order WHERE out_request_no = ? FOR UPDATE",
                request.outRequestNo());
        ObjectNode responseToStore = JSON.valueToTree(response);
        if (!existing.isEmpty()) {
            Map<String, Object> previous = existing.getFirst();
            if (!Objects.equals(previous.get("out_trade_no"), order.getOutTradeNo())
                    || !Objects.equals(previous.get("merchant_id"), order.getMerchantId())
                    || new BigDecimal(previous.get("refund_amount").toString()).compareTo(refundAmount) != 0) {
                throw refundConflict();
            }
            String previousResponse = jdbcTemplate.queryForObject("SELECT raw_response FROM refund_order WHERE out_request_no = ?",
                    String.class, request.outRequestNo());
            String knownId = storedRefundId(refundResponse(previousResponse), previous.get("code"), previous.get("message"));
            String receivedId = storedRefundId(responseToStore, null, null);
            if (hasText(knownId)) {
                if (hasText(receivedId) && !knownId.equals(receivedId)) {
                    throw new IllegalArgumentException("Platform refund ID does not match the existing refund");
                }
                responseToStore.put("verified_refund_id", knownId);
            }
        }
        String refundStatus = switch (response.status()) {
            case SUCCESS -> "SUCCESS";
            case FAILED, CLOSED -> "FAILED";
            default -> "PENDING";
        };
        jdbcTemplate.update("""
                INSERT INTO refund_order (
                    out_request_no, out_trade_no, trade_no, merchant_id, channel_id, refund_amount,
                    status, refund_reason, code, message, raw_response, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'SUCCESS' THEN CURRENT_TIMESTAMP ELSE NULL END)
                ON DUPLICATE KEY UPDATE
                    trade_no = VALUES(trade_no),
                    channel_id = VALUES(channel_id),
                    refund_amount = VALUES(refund_amount),
                    status = CASE WHEN status = 'SUCCESS' THEN status ELSE VALUES(status) END,
                    refund_reason = VALUES(refund_reason),
                    code = VALUES(code),
                    message = VALUES(message),
                    raw_response = VALUES(raw_response),
                    completed_at = COALESCE(completed_at, VALUES(completed_at))
                """,
                request.outRequestNo(),
                order.getOutTradeNo(),
                nullIfBlank(firstText(response.tradeNo(), firstText(request.tradeNo(), order.getTradeNo()))),
                order.getMerchantId(),
                existingChannelId(firstText(response.channelId(), order.getChannelId())),
                refundAmount,
                refundStatus,
                nullIfBlank(request.refundReason()),
                nullIfBlank(response.code()),
                nullIfBlank(response.message()),
                json(responseToStore),
                refundStatus
        );
    }

    private BigDecimal successfulRefundAmount(String outTradeNo) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(refund_amount), 0)
                FROM refund_order
                WHERE out_trade_no = ?
                  AND status = 'SUCCESS'
                """, BigDecimal.class, outTradeNo);
        return money(amount);
    }

    private void recordPreauthUnfreezeOrder(
            DemoOrder order,
            PreauthUnfreezeRequest request,
            GatewayResponse response,
            BigDecimal amount
    ) {
        ensurePreauthUnfreezeTable();
        jdbcTemplate.update("""
                INSERT INTO preauth_unfreeze_order (
                    out_request_no, out_trade_no, auth_no, channel_id, amount,
                    status, remark, code, message, raw_response, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SUCCESS', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    auth_no = VALUES(auth_no),
                    channel_id = VALUES(channel_id),
                    amount = VALUES(amount),
                    status = VALUES(status),
                    remark = VALUES(remark),
                    code = VALUES(code),
                    message = VALUES(message),
                    raw_response = VALUES(raw_response),
                    completed_at = VALUES(completed_at)
                """,
                request.outRequestNo(),
                order.getOutTradeNo(),
                request.authNo(),
                existingChannelId(firstText(response.channelId(), order.getChannelId())),
                amount,
                nullIfBlank(request.remark()),
                nullIfBlank(response.code()),
                nullIfBlank(response.message()),
                json(response)
        );
    }

    private BigDecimal successfulPreauthUnfrozenAmount(String outTradeNo) {
        ensurePreauthUnfreezeTable();
        BigDecimal amount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM preauth_unfreeze_order
                WHERE out_trade_no = ?
                  AND status = 'SUCCESS'
                """, BigDecimal.class, outTradeNo);
        return money(amount);
    }

    private static BigDecimal refundableAmount(DemoOrder order) {
        return money(money(order.getAmount()).subtract(money(order.getRefundedAmount())).max(BigDecimal.ZERO));
    }

    private static BigDecimal preauthUnfreezeRemainingAmount(DemoOrder order) {
        return order.getStatus() == DemoOrderStatus.UNFROZEN ? BigDecimal.ZERO.setScale(2)
                : money(money(order.getAmount()).subtract(money(order.getPreauthUnfrozenAmount()))
                .subtract(money(order.getPreauthCapturedAmount())).max(BigDecimal.ZERO));
    }

    private static void applyRefundStatus(DemoOrder order) {
        BigDecimal refunded = money(order.getRefundedAmount());
        if (refunded.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        order.setStatus(refundableAmount(order).compareTo(BigDecimal.ZERO) <= 0
                ? DemoOrderStatus.REFUNDED
                : DemoOrderStatus.PARTIALLY_REFUNDED);
    }

    private static boolean isRefundableStatus(DemoOrderStatus status) {
        return status == DemoOrderStatus.COMPLETED || status == DemoOrderStatus.PARTIALLY_REFUNDED;
    }

    private static boolean isRefundStatus(DemoOrderStatus status) {
        return status == DemoOrderStatus.PARTIALLY_REFUNDED || status == DemoOrderStatus.REFUNDED;
    }

    private static DemoOrderStatus preserveRefundStatus(DemoOrderStatus currentStatus, DemoOrderStatus nextStatus) {
        return isRefundStatus(currentStatus) ? currentStatus : firstStatus(nextStatus);
    }

    private static DemoOrderStatus preserveCollectedStatus(DemoOrderStatus currentStatus, DemoOrderStatus nextStatus) {
        return currentStatus == DemoOrderStatus.COMPLETED || currentStatus == DemoOrderStatus.UNFROZEN
                ? currentStatus : preserveRefundStatus(currentStatus, nextStatus);
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize order metadata", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static String normalizeDouyinRefundStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(status)
                || "REFUNDED".equals(status)
                || status.endsWith(".SUCCESS")
                || status.endsWith("_SUCCESS")) {
            return "SUCCESS";
        }
        if ("FAILED".equals(status)
                || "FAIL".equals(status)
                || "CLOSED".equals(status)
                || "ABNORMAL".equals(status)
                || status.endsWith(".FAILED")
                || status.endsWith("_FAILED")) {
            return "FAILED";
        }
        return "PENDING";
    }
}
