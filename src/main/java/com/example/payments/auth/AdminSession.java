package com.example.payments.auth;

import jakarta.servlet.http.HttpServletRequest;

public final class AdminSession {

    public static final String USERNAME_ATTRIBUTE = "ADMIN_USERNAME";
    public static final String CREDENTIAL_ATTRIBUTE = "ADMIN_CREDENTIAL_FINGERPRINT";

    private AdminSession() {
    }

    public static void login(HttpServletRequest request, String username, String fingerprint) {
        if (username == null || username.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Invalid administrator session credentials");
        }
        var session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(USERNAME_ATTRIBUTE, username);
        session.setAttribute(CREDENTIAL_ATTRIBUTE, fingerprint);
    }

    public static boolean isAuthenticated(HttpServletRequest request, AdminAuthService authService) {
        var session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object fingerprint = session.getAttribute(CREDENTIAL_ATTRIBUTE);
        boolean valid = fingerprint instanceof String value && authService.matchesSession(username(request), value);
        if (!valid) {
            session.removeAttribute(USERNAME_ATTRIBUTE);
            session.removeAttribute(CREDENTIAL_ATTRIBUTE);
        }
        return valid;
    }

    public static String username(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return null;
        }
        Object username = request.getSession(false).getAttribute(USERNAME_ATTRIBUTE);
        return username instanceof String value ? value : null;
    }
}
