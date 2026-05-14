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
        seed(new DemoOrder(
                "P20260512143001",
                "2026051222001419760501234567",
                "ali-main",
                "M10001",
                "示例商户A",
                "PC网页",
                new BigDecimal("299.00"),
                DemoOrderStatus.COMPLETED,
                "2026-05-12 14:30:01",
                false
        ));
        seed(new DemoOrder(
                "P20260512142830",
                null,
                "ali-backup",
                "M10002",
                "示例商户B",
                "WAP手机",
                new BigDecimal("88.00"),
                DemoOrderStatus.UNPAID,
                "2026-05-12 14:28:30",
                false
        ));
        seed(new DemoOrder(
                "P20260512141205",
                "2026051222001419760501234501",
                "ali-main",
                "M10001",
                "示例商户A",
                "当面付",
                new BigDecimal("156.50"),
                DemoOrderStatus.REFUNDED,
                "2026-05-12 14:12:05",
                false
        ));
        seed(new DemoOrder(
                "AUTH20260512140530",
                null,
                "ali-direct",
                "M10003",
                "示例商户C",
                "预授权",
                new BigDecimal("980.00"),
                DemoOrderStatus.FROZEN,
                "2026-05-12 14:05:30",
                true
        ));
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
