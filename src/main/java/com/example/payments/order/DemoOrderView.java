package com.example.payments.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record DemoOrderView(
        String outTradeNo,
        String tradeNo,
        String channelId,
        String merchantId,
        String merchantName,
        String productName,
        BigDecimal amount,
        String amountText,
        DemoOrderStatus status,
        String statusText,
        String createdAt,
        boolean preAuthorization,
        boolean supplemented,
        boolean profitShared
) {
    public static DemoOrderView from(DemoOrder order) {
        return new DemoOrderView(
                order.getOutTradeNo(),
                order.getTradeNo(),
                order.getChannelId(),
                order.getMerchantId(),
                order.getMerchantName(),
                order.getProductName(),
                order.getAmount(),
                "¥" + order.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                order.getStatus(),
                order.getStatus().getLabel(),
                order.getCreatedAt(),
                order.isPreAuthorization(),
                order.isSupplemented(),
                order.isProfitShared()
        );
    }
}
