package com.example.payments.merchant;

public record MerchantLoginRequest(
        String merchantId,
        String md5Key,
        Boolean demoLogin
) {
}
