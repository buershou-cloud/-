package com.example.payments.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PreauthUnfreezeRequest(
        String preauthOutTradeNo,
        @NotBlank String authNo,
        @NotBlank String outRequestNo,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        String remark,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
    public PreauthUnfreezeRequest withPreauthOutTradeNo(String value) {
        return new PreauthUnfreezeRequest(
                value,
                authNo,
                outRequestNo,
                amount,
                remark,
                appAuthToken,
                channelIds,
                extra
        );
    }
}
