package com.example.payments.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PayCreateRequest(
        @NotNull PaymentProduct product,
        @NotBlank String outTradeNo,
        @NotBlank String subject,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
        String authCode,
        String buyerId,
        String buyerOpenId,
        String quitUrl,
        String timeoutExpress,
        String notifyUrl,
        String returnUrl,
        String appAuthToken,
        RoutingMode routingMode,
        List<String> channelIds,
        Map<String, Object> extra,
        Map<String, Object> settleInfo,
        Map<String, Object> royaltyInfo
) {
}
