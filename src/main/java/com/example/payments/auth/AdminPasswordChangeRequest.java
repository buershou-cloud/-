package com.example.payments.auth;

public record AdminPasswordChangeRequest(
        String oldPassword,
        String newPassword
) {
}
