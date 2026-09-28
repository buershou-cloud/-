package com.example.payments.order;

import java.math.BigDecimal;

/** Read-only outgoing operations; never treated as payment orders or incoming revenue. */
public record OrderOperationView(
        String recordType,
        String orderNo,
        String tradeNo,
        String relatedOutTradeNo,
        String channelId,
        String provider,
        String merchantId,
        String merchantName,
        String subject,
        BigDecimal amount,
        String status,
        String createdAt,
        String recipient,
        String message
) {
}
