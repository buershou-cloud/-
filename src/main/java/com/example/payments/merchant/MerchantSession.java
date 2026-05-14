package com.example.payments.merchant;

import jakarta.servlet.http.HttpServletRequest;

public final class MerchantSession {

    public static final String MERCHANT_ID_ATTRIBUTE = "MERCHANT_ID";

    private MerchantSession() {
    }

    public static void login(HttpServletRequest request, String merchantId) {
        request.getSession(true).setAttribute(MERCHANT_ID_ATTRIBUTE, merchantId);
    }

    public static void logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(MERCHANT_ID_ATTRIBUTE);
        }
    }

    public static boolean isMerchant(HttpServletRequest request, String merchantId) {
        if (request.getSession(false) == null) {
            return false;
        }
        Object sessionMerchantId = request.getSession(false).getAttribute(MERCHANT_ID_ATTRIBUTE);
        return sessionMerchantId instanceof String value && value.equals(merchantId);
    }
}
