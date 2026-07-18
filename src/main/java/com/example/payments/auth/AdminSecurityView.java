package com.example.payments.auth;

import java.util.List;

public record AdminSecurityView(
        String username,
        boolean superAdministrator,
        String superAdministratorUsername,
        boolean paymentPasswordConfigured,
        List<String> administrators
) {
}
