package com.example.payments.order;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DemoOrderService {

    private static final DateTimeFormatter SERIAL_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, DemoOrder> orders = new LinkedHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public DemoOrderService() {
        this((JdbcTemplate) null);
    }

    @Autowired
    public DemoOrderService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(jdbcTemplateProvider.getIfAvailable());
    }

    private DemoOrderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public synchronized List<DemoOrderView> recent() {
        return search(null, null, null, null, null);
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
                           o.product, o.subject, o.amount, o.status, o.created_at,
                           o.pre_authorization, o.supplemented, o.profit_shared
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
        return orders.values().stream()
                .filter(order -> contains(order.getOutTradeNo(), outTradeNo))
                .filter(order -> contains(order.getTradeNo(), tradeNo))
                .filter(order -> !hasText(channelId) || Objects.equals(order.getChannelId(), channelId.trim()))
                .filter(order -> !hasText(beginTime) || compareTime(order.getCreatedAt(), beginTime) >= 0)
                .filter(order -> !hasText(endTime) || compareTime(order.getCreatedAt(), endTime) <= 0)
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized List<DemoOrderView> byMerchant(String merchantId) {
        if (databaseBacked()) {
            return jdbcTemplate.query("""
                            SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                                   COALESCE(m.name, o.merchant_id) AS merchant_name,
                                   o.product, o.subject, o.amount, o.status, o.created_at,
                                   o.pre_authorization, o.supplemented, o.profit_shared
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
                           o.product, o.subject, o.amount, o.status, o.created_at,
                           o.pre_authorization, o.supplemented, o.profit_shared
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

    public synchronized DemoOrderView complete(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.isPreAuthorization()) {
            order.setStatus(DemoOrderStatus.FROZEN);
            ensureTradeNo(order, "AUTH");
        } else {
            order.setStatus(DemoOrderStatus.COMPLETED);
            ensureTradeNo(order, "MANUAL");
        }
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
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView refund(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.COMPLETED) {
            throw new IllegalStateException("鍙湁宸插畬鎴愯鍗曞彲浠ラ€€娆?");
        }
        order.setStatus(DemoOrderStatus.REFUNDED);
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView convertPreauthToPay(String outTradeNo) {
        return convertPreauthToPay(outTradeNo, null);
    }

    public synchronized DemoOrderView convertPreauthToPay(String outTradeNo, String captureTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (!order.isPreAuthorization()) {
            throw new IllegalStateException("鍙湁棰勬巿鏉冭鍗曞彲浠ヨ浆鏀粯");
        }
        if (order.getStatus() != DemoOrderStatus.FROZEN) {
            throw new IllegalStateException("鍙湁鍐荤粨涓殑棰勬巿鏉冭鍗曞彲浠ヨ浆鏀粯");
        }
        order.setStatus(DemoOrderStatus.COMPLETED);
        order.setPreAuthorization(false);
        order.setProductName("棰勬巿鏉冭浆鏀粯");
        if (hasText(captureTradeNo)) {
            order.setTradeNo(captureTradeNo.trim());
        } else {
            ensureTradeNo(order, "CAPTURE");
        }
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView delete(String outTradeNo) {
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
        DemoOrderStatus initialStatus = statusFromGateway(paymentStatus, preAuthorization, DemoOrderStatus.UNPAID);
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) {
            order = new DemoOrder(
                    outTradeNo,
                    tradeNo,
                    channelId,
                    merchantId,
                    merchantName,
                    productName,
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
            order.setStatus(initialStatus);
        }
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized void recordPaymentMetadata(
            String outTradeNo,
            PayCreateRequest request,
            GatewayResponse response
    ) {
        if (!databaseBacked() || !hasText(outTradeNo) || request == null) {
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
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) {
            throw new IllegalArgumentException("Order does not exist: " + outTradeNo);
        }
        if (hasText(tradeNo)) {
            order.setTradeNo(tradeNo.trim());
        }
        order.setStatus(statusFromGateway(paymentStatus, order.isPreAuthorization(), order.getStatus()));
        persist(order);
        return DemoOrderView.from(order);
    }

    public synchronized void ensureMerchantOrder(String merchantId, String outTradeNo, String tradeNo) {
        if (!hasText(merchantId)) {
            throw new IllegalArgumentException("merchantId is required");
        }
        DemoOrder order = databaseBacked()
                ? findOrderByIdentifier(outTradeNo, tradeNo)
                : memoryOrderByIdentifier(outTradeNo, tradeNo);
        if (order == null) {
            throw new IllegalArgumentException("Order does not exist or does not belong to this merchant");
        }
        if (!merchantId.trim().equals(order.getMerchantId())) {
            throw new IllegalArgumentException("Order does not exist or does not belong to this merchant");
        }
    }

    public synchronized DemoOrderView recordAlipayNotify(
            String outTradeNo,
            String tradeNo,
            String channelId,
            BigDecimal amount,
            String tradeStatus
    ) {
        DemoOrder order = databaseBacked() ? findOrder(outTradeNo) : orders.get(outTradeNo);
        if (order == null) {
            order = new DemoOrder(
                    outTradeNo,
                    tradeNo,
                    channelId,
                    "M10001",
                    "榛樿鍟嗘埛",
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
            order.setStatus(statusFromAlipay(tradeStatus, order.isPreAuthorization()));
        }
        persist(order);
        return DemoOrderView.from(order);
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
                rs.getBigDecimal("amount"),
                status(rs.getString("status")),
                timestampText(rs.getTimestamp("created_at")),
                rs.getBoolean("pre_authorization")
        );
        order.setSupplemented(rs.getBoolean("supplemented"));
        order.setProfitShared(rs.getBoolean("profit_shared"));
        return order;
    }

    private DemoOrder findOrder(String outTradeNo) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT o.out_trade_no, o.trade_no, o.channel_id, o.merchant_id,
                           COALESCE(m.name, o.merchant_id) AS merchant_name,
                           o.product, o.subject, o.amount, o.status, o.created_at,
                           o.pre_authorization, o.supplemented, o.profit_shared
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
                           o.product, o.subject, o.amount, o.status, o.created_at,
                           o.pre_authorization, o.supplemented, o.profit_shared
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
                    status, pre_authorization, supplemented, profit_shared, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.getOutTradeNo(),
                nullIfBlank(order.getTradeNo()),
                order.getMerchantId(),
                existingChannelId(order.getChannelId()),
                firstText(order.getProductName(), "PAY"),
                firstText(order.getProductName(), "PAY"),
                order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                order.getStatus().name(),
                order.isPreAuthorization() ? 1 : 0,
                order.isSupplemented() ? 1 : 0,
                order.isProfitShared() ? 1 : 0,
                timestamp(order.getCreatedAt())
        );
    }

    private void updateOrder(DemoOrder order) {
        ensureMerchant(order.getMerchantId(), order.getMerchantName());
        jdbcTemplate.update("""
                UPDATE pay_order
                SET trade_no = ?, merchant_id = ?, channel_id = ?, product = ?, subject = ?,
                    amount = ?, status = ?, pre_authorization = ?, supplemented = ?, profit_shared = ?,
                    paid_at = CASE WHEN ? = 'COMPLETED' THEN COALESCE(paid_at, CURRENT_TIMESTAMP) ELSE paid_at END,
                    frozen_at = CASE WHEN ? = 'FROZEN' THEN COALESCE(frozen_at, CURRENT_TIMESTAMP) ELSE frozen_at END,
                    refunded_at = CASE WHEN ? = 'REFUNDED' THEN COALESCE(refunded_at, CURRENT_TIMESTAMP) ELSE refunded_at END,
                    closed_at = CASE WHEN ? = 'CLOSED' THEN COALESCE(closed_at, CURRENT_TIMESTAMP) ELSE closed_at END
                WHERE out_trade_no = ?
                """,
                nullIfBlank(order.getTradeNo()),
                order.getMerchantId(),
                existingChannelId(order.getChannelId()),
                firstText(order.getProductName(), "PAY"),
                firstText(order.getProductName(), "PAY"),
                order.getAmount() == null ? BigDecimal.ZERO : order.getAmount(),
                order.getStatus().name(),
                order.isPreAuthorization() ? 1 : 0,
                order.isSupplemented() ? 1 : 0,
                order.isProfitShared() ? 1 : 0,
                order.getStatus().name(),
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

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String nullIfBlank(String value) {
        return hasText(value) ? value.trim() : null;
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
}
