package com.example.payments.domain;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record ProfitSharingRequest(
        String outTradeNo,
        String tradeNo,
        @NotBlank String outRequestNo,
        @NotEmpty List<Map<String, Object>> royaltyParameters,
        String operatorId,
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
