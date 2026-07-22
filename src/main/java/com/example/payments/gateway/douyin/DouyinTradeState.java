package com.example.payments.gateway.douyin;

import com.example.payments.domain.PaymentStatus;

import java.util.Locale;

/** Maps only Douyin Pay trade states. Alipay trade states are intentionally not accepted here. */
public final class DouyinTradeState {

    private DouyinTradeState() {
    }

    public static PaymentStatus toPaymentStatus(String value) {
        String state = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (state) {
            case "SUCCESS", "REFUND" -> PaymentStatus.SUCCESS;
            case "CLOSED" -> PaymentStatus.CLOSED;
            case "NOTPAY", "USERPAYING" -> PaymentStatus.PAYING;
            case "PAYERROR" -> PaymentStatus.FAILED;
            default -> PaymentStatus.UNKNOWN;
        };
    }
}
