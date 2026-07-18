package com.example.payments.auth;

import java.util.List;

public record AdminSecurityView(
        String username,
        boolean paymentPasswordConfigured,
        List<String> administrators
) {
}
