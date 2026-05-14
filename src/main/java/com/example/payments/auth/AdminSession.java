package com.example.payments.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class AdminSession {

    public static final String USERNAME_ATTRIBUTE = "ADMIN_USERNAME";

    private AdminSession() {
    }

    public static boolean isAuthenticated(HttpServletRequest request) {
        return request.getSession(false) != null
                && request.getSession(false).getAttribute(USERNAME_ATTRIBUTE) instanceof String;
    }

    public static String username(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return null;
        }
        Object username = request.getSession(false).getAttribute(USERNAME_ATTRIBUTE);
        return username instanceof String value ? value : null;
    }
}
