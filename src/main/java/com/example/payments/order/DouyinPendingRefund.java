package com.example.payments.order;

public record DouyinPendingRefund(
        String channelId,
        String outTradeNo,
        String outRequestNo
) {
}
