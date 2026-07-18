package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ProfitSharingReturnQueryRequest(
        @NotBlank String outRequestNo,
        @NotBlank String outReturnNo,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
