package com.example.payments.domain;

public record ProfitSharingBatchItem(
        String outTradeNo,
        String tradeNo,
        String outRequestNo,
        PaymentStatus status,
        String code,
        String message
) {
}
