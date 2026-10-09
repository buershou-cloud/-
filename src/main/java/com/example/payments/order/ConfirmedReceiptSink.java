package com.example.payments.order;

import java.math.BigDecimal;

/** Receives a persisted order only after a trusted payment source confirms actual collection. */
@FunctionalInterface
public interface ConfirmedReceiptSink {
    void enqueueConfirmedReceipt(DemoOrderView order);

    default void rememberPendingCapture(DemoOrderView frozenOrder, String captureOutTradeNo, BigDecimal amount) { }

    default boolean hasPendingCapture(String parentOutTradeNo, String captureOutTradeNo,
                                      String channelId, BigDecimal amount) {
        return false;
    }
}
