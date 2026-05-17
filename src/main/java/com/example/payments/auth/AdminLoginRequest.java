package com.example.payments.auth;

public record AdminLoginRequest(
        String username,
        String password,
        String verificationId,
        Integer verificationX
) {
}
