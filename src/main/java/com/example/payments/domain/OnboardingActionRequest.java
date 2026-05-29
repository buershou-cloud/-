package com.example.payments.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record OnboardingActionRequest(
        @JsonAlias("externalId") @NotBlank String outBizNo,
        String method,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> payload
) {
}
