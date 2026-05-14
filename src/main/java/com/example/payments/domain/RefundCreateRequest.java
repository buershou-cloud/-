package com.example.payments.domain;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RefundCreateRequest(
        String outTradeNo,
        String tradeNo,
        @NotNull @DecimalMin("0.01") BigDecimal refundAmount,
        @NotBlank String outRequestNo,
        String refundReason,
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
