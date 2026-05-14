package com.example.payments.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record OnboardingRequest(
        @JsonAlias("externalId") @NotBlank String outBizNo,
        String method,
        String appAuthToken,
        List<String> channelIds,
        @NotEmpty Map<String, Object> payload
) {
}
