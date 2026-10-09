package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.notification.TelegramNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin-notifications/telegram")
public class TelegramNotificationController {
    private final TelegramNotificationService notifications;
    private final AdminAuthService authService;

    public TelegramNotificationController(TelegramNotificationService notifications, AdminAuthService authService) {
        this.notifications = notifications;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<?> settings(HttpServletRequest request) {
        if (!superAdministrator(request)) return forbidden();
        return ResponseEntity.ok(notifications.settings());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> save(@RequestBody TelegramNotificationService.SettingsUpdate update, HttpServletRequest request) {
        if (!superAdministrator(request)) return forbidden();
        return ResponseEntity.ok(notifications.saveSettings(update));
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!superAdministrator(request)) return forbidden();
        TelegramNotificationService.TestResult result = notifications.sendTest();
        return ResponseEntity.status(result.success() ? 200 : 502).body(result);
    }

    @ExceptionHandler(TelegramNotificationService.SettingsException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(TelegramNotificationService.SettingsException ex) {
        return ResponseEntity.status(ex.storage() ? 503 : 400).body(Map.of("code", ex.code(), "message", ex.getMessage()));
    }

    private boolean superAdministrator(HttpServletRequest request) {
        String username = AdminSession.username(request);
        return AdminSession.isAuthenticated(request) && authService.hasUsername(username) && authService.isSuperAdministrator(username);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("code", "SUPER_ADMIN_REQUIRED", "message", "仅超级管理员可以管理通知配置"));
    }
}
