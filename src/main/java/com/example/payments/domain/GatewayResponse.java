package com.example.payments.domain;

import java.util.List;
import java.util.Map;

public record GatewayResponse(
        String channelId,
        PaymentStatus status,
        String code,
        String message,
        String outTradeNo,
        String tradeNo,
        String qrCode,
        String redirectHtml,
        Map<String, Object> raw,
        List<ChannelAttempt> attempts
) {
    public GatewayResponse withAttempts(List<ChannelAttempt> attempts) {
        return new GatewayResponse(
                channelId,
                status,
                code,
                message,
                outTradeNo,
                tradeNo,
                qrCode,
                redirectHtml,
                raw,
                List.copyOf(attempts)
        );
    }
}
