package com.example.payments.merchant.api;

public class MerchantApiException extends IllegalArgumentException {
    private final String code;

    public MerchantApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
