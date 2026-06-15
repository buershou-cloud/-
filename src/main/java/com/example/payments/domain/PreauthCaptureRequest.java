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
        String authNo,
        @NotBlank String subject,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
        String buyerId,
        String sellerId,
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

    public PreauthCaptureRequest withAuthNo(String value) {
        return new PreauthCaptureRequest(
                preauthOutTradeNo,
                outTradeNo,
                value,
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
