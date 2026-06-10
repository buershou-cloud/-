package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ProfitSharingRelationBindRequest(
        @NotBlank String receiverAccount,
        String receiverType,
        String receiverName,
        String memo,
        @NotBlank String outRequestNo,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
