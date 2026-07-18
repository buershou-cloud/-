package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProfitSharingReturnRequest(
        @NotBlank String outRequestNo,
        @NotBlank String outReturnNo,
        @NotBlank String receiverAccount,
        @NotNull @Positive BigDecimal amount,
        String description,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
