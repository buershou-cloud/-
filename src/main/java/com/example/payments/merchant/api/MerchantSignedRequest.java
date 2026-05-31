package com.example.payments.merchant.api;

public interface MerchantSignedRequest {

    String merchantId();

    String signType();

    String timestamp();

    String nonce();

    String sign();
}
