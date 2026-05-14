package com.example.payments.order;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DemoOrderService {

    private static final DateTimeFormatter SERIAL_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Map<String, DemoOrder> orders = new LinkedHashMap<>();

    public DemoOrderService() {
        seed("P20260513171209", "2026051322001419760501299821", "ali-main", "M10001", "示例商户A", "PC网页", "1280.00", DemoOrderStatus.COMPLETED, "2026-05-13 17:12:09", false);
        seed("P20260513165422", "2026051322001419760501298750", "ali-backup", "M10002", "示例商户B", "WAP手机", "99.90", DemoOrderStatus.COMPLETED, "2026-05-13 16:54:22", false);
        seed("P20260513163018", "2026051322001419760501297618", "ali-main", "M10001", "示例商户A", "订单码", "860.00", DemoOrderStatus.COMPLETED, "2026-05-13 16:30:18", false);
        seed("P20260513161504", null, "ali-backup", "M10002", "示例商户B", "JSAPI", "46.00", DemoOrderStatus.UNPAID, "2026-05-13 16:15:04", false);
        seed("P20260513155237", "2026051322001419760501295537", "ali-direct", "M10003", "示例商户C", "直付通PC网页", "3200.00", DemoOrderStatus.COMPLETED, "2026-05-13 15:52:37", false);
        seed("P20260513152012", "2026051322001419760501294212", "ali-direct", "M10003", "示例商户C", "直付通WAP手机", "618.00", DemoOrderStatus.COMPLETED, "2026-05-13 15:20:12", false);
        seed("P20260513144833", "2026051322001419760501292833", "ali-main", "M10001", "示例商户A", "当面付", "75.50", DemoOrderStatus.REFUNDED, "2026-05-13 14:48:33", false);
        seed("AUTH20260513143001", "AUTH20260513143001", "ali-direct", "M10003", "示例商户C", "预授权", "1200.00", DemoOrderStatus.FROZEN, "2026-05-13 14:30:01", true);
        seed("P20260513135821", "2026051322001419760501290821", "ali-main", "M10001", "示例商户A", "JSAPI", "38.80", DemoOrderStatus.COMPLETED, "2026-05-13 13:58:21", false);
        seed("P20260513132210", null, "ali-backup", "M10002", "示例商户B", "PC网页", "216.00", DemoOrderStatus.UNPAID, "2026-05-13 13:22:10", false);
        seed("P20260513114607", "2026051322001419760501287607", "ali-direct", "M10003", "示例商户C", "直付通订单码", "188.00", DemoOrderStatus.COMPLETED, "2026-05-13 11:46:07", false);
        seed("P20260513102056", "2026051322001419760501286056", "ali-backup", "M10002", "示例商户B", "当面付", "430.00", DemoOrderStatus.COMPLETED, "2026-05-13 10:20:56", false);
        seed("P20260512172045", "2026051222001419760501267045", "ali-main", "M10001", "示例商户A", "WAP手机", "536.00", DemoOrderStatus.COMPLETED, "2026-05-12 17:20:45", false);
        seed("P20260512163820", "2026051222001419760501263820", "ali-direct", "M10003", "示例商户C", "直付通JSAPI", "266.00", DemoOrderStatus.REFUNDED, "2026-05-12 16:38:20", false);
        seed("P20260512160212", "2026051222001419760501260212", "ali-backup", "M10002", "示例商户B", "订单码", "720.00", DemoOrderStatus.COMPLETED, "2026-05-12 16:02:12", false);
        seed("P20260512153040", "2026051222001419760501253040", "ali-main", "M10001", "示例商户A", "APP支付", "158.00", DemoOrderStatus.COMPLETED, "2026-05-12 15:30:40", false);
        seed("P20260512143001", "2026051222001419760501234567", "ali-main", "M10001", "示例商户A", "PC网页", "299.00", DemoOrderStatus.COMPLETED, "2026-05-12 14:30:01", false);
        seed("P20260512142830", null, "ali-backup", "M10002", "示例商户B", "WAP手机", "88.00", DemoOrderStatus.UNPAID, "2026-05-12 14:28:30", false);
        seed("P20260512141205", "2026051222001419760501234501", "ali-main", "M10001", "示例商户A", "当面付", "156.50", DemoOrderStatus.REFUNDED, "2026-05-12 14:12:05", false);
        seed("AUTH20260512140530", null, "ali-direct", "M10003", "示例商户C", "预授权", "980.00", DemoOrderStatus.FROZEN, "2026-05-12 14:05:30", true);
        seed("P20260511190511", "2026051122001419760501190511", "ali-main", "M10001", "示例商户A", "订单码", "458.00", DemoOrderStatus.COMPLETED, "2026-05-11 19:05:11", false);
        seed("P20260511183008", "2026051122001419760501183008", "ali-backup", "M10002", "示例商户B", "PC网页", "1024.00", DemoOrderStatus.COMPLETED, "2026-05-11 18:30:08", false);
        seed("P20260511170633", "2026051122001419760501170633", "ali-direct", "M10003", "示例商户C", "直付通当面付", "89.90", DemoOrderStatus.COMPLETED, "2026-05-11 17:06:33", false);
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
        return orders.values().stream()
                .filter(order -> Objects.equals(order.getMerchantId(), merchantId))
                .map(DemoOrderView::from)
                .toList();
    }

    public synchronized List<DemoOrderView> shareableByChannel(String channelId, boolean includeProfitShared) {
        if (!hasText(channelId)) {
            throw new IllegalArgumentException("支付通道不能为空");
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
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView uncomplete(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.COMPLETED) {
            throw new IllegalStateException("只有已完成订单可以改未完成");
        }
        order.setStatus(DemoOrderStatus.UNPAID);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView manualSupplement(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.UNPAID) {
            throw new IllegalStateException("只有未支付订单可以手动补单");
        }
        order.setStatus(order.isPreAuthorization() ? DemoOrderStatus.FROZEN : DemoOrderStatus.COMPLETED);
        order.setSupplemented(true);
        ensureTradeNo(order, order.isPreAuthorization() ? "AUTH_SUPP" : "SUPP");
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView refund(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (order.getStatus() != DemoOrderStatus.COMPLETED) {
            throw new IllegalStateException("只有已完成订单可以退款");
        }
        order.setStatus(DemoOrderStatus.REFUNDED);
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView convertPreauthToPay(String outTradeNo) {
        DemoOrder order = order(outTradeNo);
        if (!order.isPreAuthorization()) {
            throw new IllegalStateException("只有预授权订单可以转支付");
        }
        if (order.getStatus() != DemoOrderStatus.FROZEN) {
            throw new IllegalStateException("只有冻结中的预授权订单可以转支付");
        }
        order.setStatus(DemoOrderStatus.COMPLETED);
        order.setPreAuthorization(false);
        order.setProductName("预授权转支付");
        ensureTradeNo(order, "CAPTURE");
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView delete(String outTradeNo) {
        DemoOrder removed = orders.remove(outTradeNo);
        if (removed == null) {
            throw new IllegalArgumentException("订单不存在: " + outTradeNo);
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
        DemoOrder order = orders.get(outTradeNo);
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
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
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
        return DemoOrderView.from(order);
    }

    public synchronized DemoOrderView recordAlipayNotify(
            String outTradeNo,
            String tradeNo,
            String channelId,
            BigDecimal amount,
            String tradeStatus
    ) {
        DemoOrder order = orders.get(outTradeNo);
        if (order == null) {
            order = new DemoOrder(
                    outTradeNo,
                    tradeNo,
                    channelId,
                    "M10001",
                    "默认商户",
                    "支付宝支付",
                    amount == null ? BigDecimal.ZERO : amount,
                    statusFromAlipay(tradeStatus, false),
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    false
            );
            seed(order);
        } else {
            if (hasText(tradeNo)) {
                order.setTradeNo(tradeNo.trim());
            }
            order.setStatus(statusFromAlipay(tradeStatus, order.isPreAuthorization()));
        }
        return DemoOrderView.from(order);
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

    private DemoOrder order(String outTradeNo) {
        DemoOrder order = orders.get(outTradeNo);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + outTradeNo);
        }
        return order;
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

    private static boolean contains(String value, String needle) {
        return !hasText(needle) || (value != null && value.contains(needle.trim()));
    }

    private static int compareTime(String left, String right) {
        return normalizeTime(left).compareTo(normalizeTime(right));
    }

    private static String normalizeTime(String value) {
        return value == null ? "" : value.trim().replace('T', ' ');
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
