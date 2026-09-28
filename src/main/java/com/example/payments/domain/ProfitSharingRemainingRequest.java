package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** Remaining funds belong to a payment, even before its first split request. */
public record ProfitSharingRemainingRequest(
        String outTradeNo,
        @NotBlank String tradeNo,
        String outRequestNo,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
    public ProfitSharingQueryRequest toQueryRequest() {
        return new ProfitSharingQueryRequest(outTradeNo, tradeNo, outRequestNo, appAuthToken, channelIds, extra);
    }
}
