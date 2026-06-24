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
        String subject,
        BigDecimal amount,
        String amountText,
        BigDecimal refundedAmount,
        String refundedAmountText,
        BigDecimal refundableAmount,
        String refundableAmountText,
        BigDecimal preauthUnfrozenAmount,
        String preauthUnfrozenAmountText,
        BigDecimal preauthUnfreezeRemainingAmount,
        String preauthUnfreezeRemainingAmountText,
        DemoOrderStatus status,
        String statusText,
        String createdAt,
        boolean preAuthorization,
        boolean supplemented,
        boolean profitShared
) {
    public static DemoOrderView from(DemoOrder order) {
        BigDecimal amount = money(order.getAmount());
        BigDecimal refundedAmount = money(order.getRefundedAmount());
        BigDecimal refundableAmount = money(amount.subtract(refundedAmount).max(BigDecimal.ZERO));
        BigDecimal preauthUnfrozenAmount = money(order.getPreauthUnfrozenAmount());
        BigDecimal preauthUnfreezeRemainingAmount = money(amount.subtract(preauthUnfrozenAmount).max(BigDecimal.ZERO));
        return new DemoOrderView(
                order.getOutTradeNo(),
                order.getTradeNo(),
                order.getChannelId(),
                order.getMerchantId(),
                order.getMerchantName(),
                order.getProductName(),
                order.getSubject(),
                amount,
                amountText(amount),
                refundedAmount,
                amountText(refundedAmount),
                refundableAmount,
                amountText(refundableAmount),
                preauthUnfrozenAmount,
                amountText(preauthUnfrozenAmount),
                preauthUnfreezeRemainingAmount,
                amountText(preauthUnfreezeRemainingAmount),
                order.getStatus(),
                order.getStatus().getLabel(),
                order.getCreatedAt(),
                order.isPreAuthorization(),
                order.isSupplemented(),
                order.isProfitShared()
        );
    }

    private static BigDecimal money(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static String amountText(BigDecimal amount) {
        return "¥" + money(amount).toPlainString();
    }
}
