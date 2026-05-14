package com.example.payments.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record OnboardingRequest(
        @NotBlank String outBizNo,
        String method,
        String appAuthToken,
        List<String> channelIds,
        @NotEmpty Map<String, Object> payload
) {
}
