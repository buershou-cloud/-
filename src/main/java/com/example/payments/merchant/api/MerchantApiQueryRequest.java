package com.example.payments.merchant.api;

import com.example.payments.domain.PaymentQueryRequest;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record MerchantApiQueryRequest(
        @NotBlank String merchantId,
        @NotBlank String outTradeNo,
        String tradeNo,
        List<String> channelIds,
        @NotBlank String signType,
        @NotBlank String timestamp,
        @NotBlank String nonce,
        @NotBlank String sign
) implements MerchantSignedRequest {

    public PaymentQueryRequest toPaymentQueryRequest(List<String> safeChannelIds) {
        return new PaymentQueryRequest(
                outTradeNo,
                tradeNo,
                null,
                safeChannelIds,
                Map.of("merchantId", merchantId)
        );
    }
}
