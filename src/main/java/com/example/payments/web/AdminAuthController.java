package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminLoginRequest;
import com.example.payments.auth.AdminPasswordChangeRequest;
import com.example.payments.auth.AdminSession;
import com.example.payments.auth.AdminSessionView;
import com.example.payments.auth.GraphicChallengeView;
import com.example.payments.auth.GraphicVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
        return new AdminSessionView(AdminSession.isAuthenticated(request), AdminSession.username(request));
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
        request.getSession(true).setAttribute(AdminSession.USERNAME_ATTRIBUTE, authService.username());
        return new AdminSessionView(true, authService.username());
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
        authService.changePassword(changeRequest.oldPassword(), changeRequest.newPassword());
        request.getSession(true).setAttribute(AdminSession.USERNAME_ATTRIBUTE, authService.username());
        return new AdminSessionView(true, authService.username());
    }
}
