package com.example.payments.order;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ConfirmedReceiptTestSupport {
    private ConfirmedReceiptTestSupport() { }

    public static DemoOrderService orders(ConfirmedReceiptSink sink) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("receipts", sink);
        return new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class),
                beans.getBeanProvider(ConfirmedReceiptSink.class));
    }

    /** Mirrors the durable sink's parent-order idempotency without making any network requests. */
    public static final class MemorySink implements ConfirmedReceiptSink {
        public final Map<String, DemoOrderView> receipts = new LinkedHashMap<>();
        public final Map<String, Capture> captures = new LinkedHashMap<>();

        @Override
        public void enqueueConfirmedReceipt(DemoOrderView order) {
            receipts.putIfAbsent(order.outTradeNo(), order);
        }

        @Override
        public void rememberPendingCapture(DemoOrderView frozenOrder, String captureOutTradeNo, BigDecimal amount) {
            captures.putIfAbsent(captureOutTradeNo, new Capture(frozenOrder.outTradeNo(), frozenOrder.channelId(), amount));
        }

        @Override
        public boolean hasPendingCapture(String parentOutTradeNo, String captureOutTradeNo,
                                         String channelId, BigDecimal amount) {
            Capture capture = captures.get(captureOutTradeNo);
            return capture != null && Objects.equals(parentOutTradeNo, capture.parent())
                    && Objects.equals(channelId, capture.channel()) && amount != null
                    && capture.amount().compareTo(amount) == 0;
        }
    }

    public record Capture(String parent, String channel, BigDecimal amount) { }
}
