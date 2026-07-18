package com.example.payments.auth;

public record AdminAccountCreateRequest(
        String currentPassword,
        String username,
        String password
) {
}
