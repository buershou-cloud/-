package com.example.payments.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

final class RequestUrlSupport {

    private RequestUrlSupport() {
    }

    static String apiBase(HttpServletRequest request) {
        return origin(request) + "/api/v1";
    }

    static String alipayNotifyUrl(HttpServletRequest request, String channelId) {
        return apiBase(request) + "/alipay/notify/" + encode(channelId);
    }

    static String alipayReturnUrl(HttpServletRequest request, String channelId) {
        return apiBase(request) + "/alipay/return/" + encode(channelId);
    }

    private static String origin(HttpServletRequest request) {
        String scheme = firstNonBlank(firstHeader(request, "X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(firstHeader(request, "X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null) {
            host = request.getServerName();
            if (!isDefaultPort(scheme, request.getServerPort())) {
                host = host + ":" + request.getServerPort();
            }
        }

        String forwardedPort = firstHeader(request, "X-Forwarded-Port");
        if (forwardedPort != null && !hasPort(host) && !isDefaultPort(scheme, forwardedPort)) {
            host = host + ":" + forwardedPort;
        }
        return scheme + "://" + host;
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = comma >= 0 ? value.substring(0, comma) : value;
        first = first.trim();
        return first.isBlank() ? null : first;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean hasPort(String host) {
        if (host.startsWith("[")) {
            return host.contains("]:");
        }
        return host.contains(":");
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
    }

    private static boolean isDefaultPort(String scheme, String port) {
        try {
            return isDefaultPort(scheme, Integer.parseInt(port));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
