package com.example.payments.gateway.alipay;

public record AlipayRequestOptions(
        String appAuthToken,
        String notifyUrl,
        String returnUrl
) {
}
