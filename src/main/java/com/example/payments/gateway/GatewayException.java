package com.example.payments.gateway;

public class GatewayException extends RuntimeException {

    private final String code;

    public GatewayException(String code, String message) {
        super(message);
        this.code = code;
    }

    public GatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
