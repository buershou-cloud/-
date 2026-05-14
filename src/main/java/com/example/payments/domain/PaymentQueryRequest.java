package com.example.payments.domain;

import jakarta.validation.constraints.AssertTrue;

import java.util.List;
import java.util.Map;

public record PaymentQueryRequest(
        String outTradeNo,
        String tradeNo,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
    @AssertTrue(message = "outTradeNo or tradeNo is required")
    public boolean hasTradeIdentifier() {
        return hasText(outTradeNo) || hasText(tradeNo);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
