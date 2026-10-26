package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Identity and money from a verified refund response, shared by callbacks and reconciliation. */
public record DouyinRefundEvidence(String outRefundNo, String outTradeNo, String transactionId,
                                   String refundId, BigDecimal refundAmount, BigDecimal totalAmount) {
    public static DouyinRefundEvidence fromNotification(PaymentGatewayProperties.Channel channel,
                                                        Map<String, Object> payload, String resourceMerchant) {
        return parse(channel, payload, resourceMerchant, true);
    }

    public static DouyinRefundEvidence fromQueryResponse(PaymentGatewayProperties.Channel channel, Map<String, Object> raw) {
        // The official Refund response has no mchid; the outbound request is scoped to this channel's mchid.
        return parse(channel, raw, null, false);
    }

    private static DouyinRefundEvidence parse(PaymentGatewayProperties.Channel channel, Map<String, Object> raw,
                                              String resourceMerchant, boolean notification) {
        Map<?, ?> payload = raw != null && raw.get("data") instanceof Map<?, ?> data ? data : raw;
        if (payload == null || !"DOUYIN".equalsIgnoreCase(channel.getProvider())) {
            throw new IllegalArgumentException("Invalid Douyin refund evidence");
        }
        String merchant = text(payload, "mchid");
        String app = text(payload, "appid");
        if ((notification && merchant == null && (resourceMerchant == null || resourceMerchant.isBlank()))
                || (merchant != null && !merchant.equals(channel.getDouyin().getMchId()))
                || (resourceMerchant != null && !resourceMerchant.equals(channel.getDouyin().getMchId()))
                || (app != null && !app.equals(channel.getDouyin().getAppId()))) {
            throw new IllegalArgumentException("Douyin refund merchant does not match the channel");
        }
        String refundNo = first(text(payload, "out_refund_no"), text(payload, "out_request_no"));
        String orderNo = text(payload, "out_trade_no");
        String transactionId = text(payload, "transaction_id");
        String refundId = first(text(payload, "refund_id"), text(payload, "refund_no"));
        if (refundNo == null || (orderNo == null && transactionId == null) || refundId == null
                || !(payload.get("amount") instanceof Map<?, ?> amount)) {
            throw new IllegalArgumentException("Douyin refund identity or amount is missing");
        }
        String currency = text(amount, "currency");
        if (currency != null && !"CNY".equals(currency)) {
            throw new IllegalArgumentException("Douyin refund currency does not match the order");
        }
        return new DouyinRefundEvidence(refundNo, orderNo, transactionId, refundId,
                yuan(amount.get("refund")), amount.containsKey("total") ? yuan(amount.get("total")) : null);
    }

    private static BigDecimal yuan(Object fen) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(fen)).setScale(0, RoundingMode.UNNECESSARY).movePointLeft(2);
            if (value.signum() <= 0) throw new IllegalArgumentException("Douyin refund amount must be positive");
            return value;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("Invalid Douyin refund amount", ex);
        }
    }

    private static String text(Map<?, ?> map, String key) {
        return map.get(key) instanceof String value && !value.isBlank() ? value.trim() : null;
    }

    private static String first(String value, String fallback) { return value != null ? value : fallback; }
}
