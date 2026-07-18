package com.example.payments.auth;

public record AdminUsernameChangeRequest(
        String currentPassword,
        String newUsername
) {
}
