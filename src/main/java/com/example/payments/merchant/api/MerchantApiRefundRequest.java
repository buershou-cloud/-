package com.example.payments.merchant.api;

import com.example.payments.domain.RefundCreateRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MerchantApiRefundRequest(
        @NotBlank String merchantId,
        @NotBlank String outTradeNo,
        String tradeNo,
        @NotNull @DecimalMin("0.01") BigDecimal refundAmount,
        @NotBlank String outRequestNo,
        String refundReason,
        List<String> channelIds,
        @NotBlank String signType,
        @NotBlank String timestamp,
        @NotBlank String nonce,
        @NotBlank String sign
) implements MerchantSignedRequest {

    public RefundCreateRequest toRefundCreateRequest(List<String> safeChannelIds) {
        return new RefundCreateRequest(
                outTradeNo,
                tradeNo,
                refundAmount,
                outRequestNo,
                refundReason,
                null,
                safeChannelIds,
                Map.of("merchantId", merchantId)
        );
    }
}
