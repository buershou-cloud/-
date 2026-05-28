package com.example.payments.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PreauthCaptureRequest(
        String preauthOutTradeNo,
        @NotBlank String outTradeNo,
        @NotBlank String authNo,
        @NotBlank String subject,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
        @NotBlank String buyerId,
        @NotBlank String sellerId,
        String authConfirmMode,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
    public PreauthCaptureRequest withPreauthOutTradeNo(String value) {
        return new PreauthCaptureRequest(
                value,
                outTradeNo,
                authNo,
                subject,
                totalAmount,
                buyerId,
                sellerId,
                authConfirmMode,
                appAuthToken,
                channelIds,
                extra
        );
    }
}
