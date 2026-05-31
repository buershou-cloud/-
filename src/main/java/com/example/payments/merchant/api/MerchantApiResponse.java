package com.example.payments.merchant.api;

public record MerchantApiResponse<T>(
        String code,
        String message,
        T data,
        String timestamp,
        String signType,
        String sign
) {
}
