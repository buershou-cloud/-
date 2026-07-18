package com.example.payments.payout;

import java.math.BigDecimal;

public record MerchantPayoutView(
        long id,
        String outBizNo,
        String provider,
        String channelId,
        String recipientType,
        String recipientMasked,
        String recipientNameMasked,
        BigDecimal amount,
        String orderTitle,
        String remark,
        String transferSceneId,
        String platformOrderNo,
        String platformFundOrderNo,
        String status,
        String code,
        String message,
        String failReason,
        String createdAt,
        String updatedAt,
        String completedAt
) {
}
