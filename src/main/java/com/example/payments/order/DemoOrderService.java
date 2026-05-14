package com.example.payments.order;

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
        if (databaseBacked()) {
            seedDatabaseIfEmpty();
        } else {
            seedMemory();
        }
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
        ensureTradeNo(order, "CAPTURE");
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
            boolean preAuthorization
    ) {
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
                    preAuthorization ? DemoOrderStatus.FROZEN : DemoOrderStatus.UNPAID,
                    LocalDateTime.now().format(DISPLAY_TIME),
                    preAuthorization
            );
            seed(order);
        } else {
            if (hasText(tradeNo)) {
                order.setTradeNo(tradeNo.trim());
            }
            if (preAuthorization) {
                order.setStatus(DemoOrderStatus.FROZEN);
            }
        }
        persist(order);
        return DemoOrderView.from(order);
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

    private void seedMemory() {
        seed("P20260513171209", "2026051322001419760501299821", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "PC缃戦〉", "1280.00", DemoOrderStatus.COMPLETED, "2026-05-13 17:12:09", false);
        seed("P20260513165422", "2026051322001419760501298750", "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "WAP鎵嬫満", "99.90", DemoOrderStatus.COMPLETED, "2026-05-13 16:54:22", false);
        seed("P20260513163018", "2026051322001419760501297618", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "璁㈠崟鐮?", "860.00", DemoOrderStatus.COMPLETED, "2026-05-13 16:30:18", false);
        seed("P20260513161504", null, "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "JSAPI", "46.00", DemoOrderStatus.UNPAID, "2026-05-13 16:15:04", false);
        seed("P20260513155237", "2026051322001419760501295537", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "鐩翠粯閫歅C缃戦〉", "3200.00", DemoOrderStatus.COMPLETED, "2026-05-13 15:52:37", false);
        seed("P20260513152012", "2026051322001419760501294212", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "鐩翠粯閫歐AP鎵嬫満", "618.00", DemoOrderStatus.COMPLETED, "2026-05-13 15:20:12", false);
        seed("P20260513144833", "2026051322001419760501292833", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "褰撻潰浠?", "75.50", DemoOrderStatus.REFUNDED, "2026-05-13 14:48:33", false);
        seed("AUTH20260513143001", "AUTH20260513143001", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "棰勬巿鏉?", "1200.00", DemoOrderStatus.FROZEN, "2026-05-13 14:30:01", true);
        seed("P20260513135821", "2026051322001419760501290821", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "JSAPI", "38.80", DemoOrderStatus.COMPLETED, "2026-05-13 13:58:21", false);
        seed("P20260513132210", null, "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "PC缃戦〉", "216.00", DemoOrderStatus.UNPAID, "2026-05-13 13:22:10", false);
        seed("P20260513114607", "2026051322001419760501287607", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "鐩翠粯閫氳鍗曠爜", "188.00", DemoOrderStatus.COMPLETED, "2026-05-13 11:46:07", false);
        seed("P20260513102056", "2026051322001419760501286056", "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "褰撻潰浠?", "430.00", DemoOrderStatus.COMPLETED, "2026-05-13 10:20:56", false);
        seed("P20260512172045", "2026051222001419760501267045", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "WAP鎵嬫満", "536.00", DemoOrderStatus.COMPLETED, "2026-05-12 17:20:45", false);
        seed("P20260512163820", "2026051222001419760501263820", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "鐩翠粯閫欽SAPI", "266.00", DemoOrderStatus.REFUNDED, "2026-05-12 16:38:20", false);
        seed("P20260512160212", "2026051222001419760501260212", "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "璁㈠崟鐮?", "720.00", DemoOrderStatus.COMPLETED, "2026-05-12 16:02:12", false);
        seed("P20260512153040", "2026051222001419760501253040", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "APP鏀粯", "158.00", DemoOrderStatus.COMPLETED, "2026-05-12 15:30:40", false);
        seed("P20260512143001", "2026051222001419760501234567", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "PC缃戦〉", "299.00", DemoOrderStatus.COMPLETED, "2026-05-12 14:30:01", false);
        seed("P20260512142830", null, "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "WAP鎵嬫満", "88.00", DemoOrderStatus.UNPAID, "2026-05-12 14:28:30", false);
        seed("P20260512141205", "2026051222001419760501234501", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "褰撻潰浠?", "156.50", DemoOrderStatus.REFUNDED, "2026-05-12 14:12:05", false);
        seed("AUTH20260512140530", null, "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "棰勬巿鏉?", "980.00", DemoOrderStatus.FROZEN, "2026-05-12 14:05:30", true);
        seed("P20260511190511", "2026051122001419760501190511", "ali-main", "M10001", "绀轰緥鍟嗘埛A", "璁㈠崟鐮?", "458.00", DemoOrderStatus.COMPLETED, "2026-05-11 19:05:11", false);
        seed("P20260511183008", "2026051122001419760501183008", "ali-backup", "M10002", "绀轰緥鍟嗘埛B", "PC缃戦〉", "1024.00", DemoOrderStatus.COMPLETED, "2026-05-11 18:30:08", false);
        seed("P20260511170633", "2026051122001419760501170633", "ali-direct", "M10003", "绀轰緥鍟嗘埛C", "鐩翠粯閫氬綋闈粯", "89.90", DemoOrderStatus.COMPLETED, "2026-05-11 17:06:33", false);
    }

    private void seedDatabaseIfEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pay_order", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        Integer merchants = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchant", Integer.class);
        Integer channels = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pay_channel", Integer.class);
        if (merchants == null || merchants == 0 || channels == null || channels == 0) {
            return;
        }
        seedMemory();
        for (DemoOrder order : orders.values()) {
            insertOrder(order);
        }
        orders.clear();
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
                    refunded_at = CASE WHEN ? = 'REFUNDED' THEN COALESCE(refunded_at, CURRENT_TIMESTAMP) ELSE refunded_at END
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

    private void seed(
            String outTradeNo,
            String tradeNo,
            String channelId,
            String merchantId,
            String merchantName,
            String productName,
            String amount,
            DemoOrderStatus status,
            String createdAt,
            boolean preAuthorization
    ) {
        seed(new DemoOrder(
                outTradeNo,
                tradeNo,
                channelId,
                merchantId,
                merchantName,
                productName,
                new BigDecimal(amount),
                status,
                createdAt,
                preAuthorization
        ));
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
            return DemoOrderStatus.REFUNDED;
        }
        return DemoOrderStatus.UNPAID;
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
