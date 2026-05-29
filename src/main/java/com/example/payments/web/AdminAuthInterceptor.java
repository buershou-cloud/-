package com.example.payments.web;

import com.example.payments.auth.AdminSession;
import com.example.payments.merchant.MerchantSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_PAGES = Set.of(
            "/login.html",
            "/merchant.html",
            "/cashier.html",
            "/favicon.ico",
            "/error"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = normalizedPath(request);
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method) || isPublic(path, method, request) || AdminSession.isAuthenticated(request)) {
            return true;
        }

        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录后台\"}");
            return false;
        }

        response.sendRedirect(request.getContextPath() + "/login.html");
        return false;
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.isBlank() ? "/" : path;
    }

    private static boolean isPublic(String path, String method, HttpServletRequest request) {
        if (PUBLIC_PAGES.contains(path)) {
            return true;
        }
        if (path.startsWith("/actuator")) {
            return true;
        }
        if (path.equals("/api/v1/admin-auth/login")
                || path.equals("/api/v1/admin-auth/session")
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/admin-auth/graphic-challenge"))) {
            return true;
        }
        if (path.equals("/api/v1/merchant-portal/login") || path.equals("/api/v1/merchant-portal/logout")) {
            return true;
        }
        if (path.startsWith("/api/v1/alipay/")
                || path.equals("/api/v1/qrcode")
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/payment-code/decode"))) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/channels")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/channels/[^/]+/cashier-qr")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/merchants/[^/]+/(cashier-info|cashier-qr)")) {
            return true;
        }
        if (path.matches("/api/v1/merchants/[^/]+/(reset-md5|generate-rsa2|sign-mode)")) {
            return MerchantSession.isMerchant(request, merchantId(path));
        }
        return "POST".equalsIgnoreCase(method) && path.equals("/api/v1/payments/pay");
    }

    private static String merchantId(String path) {
        String prefix = "/api/v1/merchants/";
        String suffix = path.substring(prefix.length());
        int slash = suffix.indexOf('/');
        return slash >= 0 ? suffix.substring(0, slash) : suffix;
    }
}
