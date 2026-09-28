package com.example.payments.merchant.api;

import jakarta.validation.constraints.NotBlank;

public record MerchantApiCheckRequest(
        @NotBlank String merchantId,
        @NotBlank String signType,
        @NotBlank String timestamp,
        @NotBlank String nonce,
        @NotBlank String sign
) implements MerchantSignedRequest {
}
