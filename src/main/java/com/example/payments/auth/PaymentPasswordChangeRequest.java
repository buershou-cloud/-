package com.example.payments.auth;

public record PaymentPasswordChangeRequest(
        String adminPassword,
        String currentPaymentPassword,
        String newPaymentPassword
) {
}
