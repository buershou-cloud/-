package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public record DemoMerchantView(
        String merchantId,
        String name,
        BigDecimal feeRate,
        String feeRateText,
        String status,
        BigDecimal todayAmount,
        String todayAmountText,
        String settlementStatus,
        String md5Key,
        String platformPublicKey,
        String rsa2PublicKey,
        String rsa2PrivateKey,
        String signMode,
        Set<String> channelIds,
        RoutingMode routingMode,
        String routingModeText
) {
    public static DemoMerchantView from(DemoMerchant merchant) {
        return new DemoMerchantView(
                merchant.getMerchantId(),
                merchant.getName(),
                merchant.getFeeRate(),
                merchant.getFeeRate().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%",
                merchant.getStatus(),
                merchant.getTodayAmount(),
                "¥" + merchant.getTodayAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                merchant.getSettlementStatus(),
                merchant.getMd5Key(),
                merchant.getPlatformPublicKey(),
                merchant.getRsa2PublicKey(),
                merchant.getRsa2PrivateKey(),
                merchant.getSignMode(),
                merchant.getChannelIds(),
                merchant.getRoutingMode(),
                routingModeText(merchant.getRoutingMode())
        );
    }

    private static String routingModeText(RoutingMode routingMode) {
        return switch (routingMode == null ? RoutingMode.ROUND_ROBIN : routingMode) {
            case ROUND_ROBIN -> "轮询";
            case RANDOM -> "随机";
            case PRIORITY -> "顺序";
            case WEIGHTED_RANDOM -> "权重随机";
        };
    }
}
