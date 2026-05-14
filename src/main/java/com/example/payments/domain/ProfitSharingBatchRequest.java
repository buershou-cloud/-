package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Map;

public record ProfitSharingBatchRequest(
        @NotBlank String channelId,
        @NotBlank String transIn,
        String transInType,
        BigDecimal amount,
        String desc,
        String outRequestNoPrefix,
        String operatorId,
        String appAuthToken,
        boolean includeProfitShared,
        Map<String, Object> extra
) {
}
