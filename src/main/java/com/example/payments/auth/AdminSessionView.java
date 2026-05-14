package com.example.payments.auth;

public record AdminSessionView(
        boolean authenticated,
        String username
) {
}
