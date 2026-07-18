package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminAccountCreateRequest;
import com.example.payments.auth.AdminAccountDeleteRequest;
import com.example.payments.auth.AdminLoginRequest;
import com.example.payments.auth.AdminPasswordChangeRequest;
import com.example.payments.auth.AdminSession;
import com.example.payments.auth.AdminSessionView;
import com.example.payments.auth.AdminSecurityView;
import com.example.payments.auth.AdminUsernameChangeRequest;
import com.example.payments.auth.GraphicChallengeView;
import com.example.payments.auth.GraphicVerificationService;
import com.example.payments.auth.PaymentPasswordChangeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin-auth")
public class AdminAuthController {

    private final AdminAuthService authService;
    private final GraphicVerificationService graphicVerificationService;

    public AdminAuthController(AdminAuthService authService, GraphicVerificationService graphicVerificationService) {
        this.authService = authService;
        this.graphicVerificationService = graphicVerificationService;
    }

    @GetMapping("/session")
    public AdminSessionView session(HttpServletRequest request) {
        String username = AdminSession.username(request);
        boolean authenticated = AdminSession.isAuthenticated(request) && authService.hasUsername(username);
        return new AdminSessionView(authenticated, authenticated ? username : null);
    }

    @GetMapping("/graphic-challenge")
    public GraphicChallengeView graphicChallenge(HttpServletRequest request) {
        return graphicVerificationService.createChallenge(request);
    }

    @PostMapping("/login")
    public AdminSessionView login(
            @RequestBody AdminLoginRequest loginRequest,
            HttpServletRequest request
    ) {
        if (!graphicVerificationService.verify(request, loginRequest.verificationId(), loginRequest.verificationX())) {
            throw new IllegalArgumentException("图形验证未通过，请重新验证");
        }
        if (!authService.verify(loginRequest.username(), loginRequest.password())) {
            throw new IllegalArgumentException("账号或密码不正确");
        }
        String username = loginRequest.username().trim();
        request.getSession(true).setAttribute(AdminSession.USERNAME_ATTRIBUTE, username);
        return new AdminSessionView(true, username);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("message", "已退出后台");
    }

    @PostMapping("/password")
    public AdminSessionView changePassword(
            @RequestBody AdminPasswordChangeRequest changeRequest,
            HttpServletRequest request
    ) {
        if (!AdminSession.isAuthenticated(request)) {
            throw new IllegalStateException("请先登录后台");
        }
        String username = requiredSessionUsername(request);
        authService.changePassword(username, changeRequest.oldPassword(), changeRequest.newPassword());
        return new AdminSessionView(true, username);
    }

    @GetMapping("/security")
    public AdminSecurityView security(HttpServletRequest request) {
        return securityView(requiredSessionUsername(request));
    }

    @PostMapping("/username")
    public AdminSecurityView changeUsername(
            @RequestBody AdminUsernameChangeRequest changeRequest,
            HttpServletRequest request
    ) {
        String currentUsername = requiredSessionUsername(request);
        authService.changeUsername(currentUsername, changeRequest.currentPassword(), changeRequest.newUsername());
        String newUsername = changeRequest.newUsername().trim();
        request.getSession(true).setAttribute(AdminSession.USERNAME_ATTRIBUTE, newUsername);
        return securityView(newUsername);
    }

    @PostMapping("/accounts")
    public AdminSecurityView addAdministrator(
            @RequestBody AdminAccountCreateRequest createRequest,
            HttpServletRequest request
    ) {
        String currentUsername = requiredSessionUsername(request);
        authService.addAdministrator(
                currentUsername,
                createRequest.currentPassword(),
                createRequest.username(),
                createRequest.password()
        );
        return securityView(currentUsername);
    }

    @DeleteMapping("/accounts/{username}")
    public AdminSecurityView removeAdministrator(
            @PathVariable String username,
            @RequestBody AdminAccountDeleteRequest deleteRequest,
            HttpServletRequest request
    ) {
        String currentUsername = requiredSessionUsername(request);
        authService.removeAdministrator(currentUsername, deleteRequest.currentPassword(), username);
        return securityView(currentUsername);
    }

    @PostMapping("/payment-password")
    public AdminSecurityView changePaymentPassword(
            @RequestBody PaymentPasswordChangeRequest changeRequest,
            HttpServletRequest request
    ) {
        String username = requiredSessionUsername(request);
        authService.changePaymentPassword(
                username,
                changeRequest.adminPassword(),
                changeRequest.currentPaymentPassword(),
                changeRequest.newPaymentPassword()
        );
        return securityView(username);
    }

    private AdminSecurityView securityView(String username) {
        return new AdminSecurityView(username, authService.paymentPasswordConfigured(), authService.usernames());
    }

    private static String requiredSessionUsername(HttpServletRequest request) {
        String username = AdminSession.username(request);
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("请先登录后台");
        }
        return username;
    }
}
