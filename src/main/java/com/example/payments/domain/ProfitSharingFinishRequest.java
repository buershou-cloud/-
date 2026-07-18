package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ProfitSharingFinishRequest(
        String outTradeNo,
        @NotBlank String tradeNo,
        @NotBlank String outRequestNo,
        String description,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
