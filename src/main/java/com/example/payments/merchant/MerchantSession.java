package com.example.payments.merchant;

import jakarta.servlet.http.HttpServletRequest;

public final class MerchantSession {

    public static final String MERCHANT_ID_ATTRIBUTE = "MERCHANT_ID";
    public static final String CREDENTIAL_ATTRIBUTE = "MERCHANT_CREDENTIAL_FINGERPRINT";

    private MerchantSession() {
    }

    public static void login(HttpServletRequest request, DemoMerchantView merchant) {
        var session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(MERCHANT_ID_ATTRIBUTE, merchant.merchantId());
        session.setAttribute(CREDENTIAL_ATTRIBUTE, DemoMerchantService.portalSessionFingerprint(merchant));
    }

    public static void logout(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.getSession(false).removeAttribute(MERCHANT_ID_ATTRIBUTE);
            request.getSession(false).removeAttribute(CREDENTIAL_ATTRIBUTE);
        }
    }

    public static boolean isMerchant(HttpServletRequest request, String merchantId, DemoMerchantService merchantService) {
        if (request.getSession(false) == null) {
            return false;
        }
        Object sessionMerchantId = request.getSession(false).getAttribute(MERCHANT_ID_ATTRIBUTE);
        if (!(sessionMerchantId instanceof String value) || !value.equals(merchantId)) {
            return false;
        }
        Object fingerprint = request.getSession(false).getAttribute(CREDENTIAL_ATTRIBUTE);
        boolean valid = fingerprint instanceof String credential && merchantService.matchesPortalSession(merchantId, credential);
        if (!valid) {
            logout(request);
        }
        return valid;
    }
}
