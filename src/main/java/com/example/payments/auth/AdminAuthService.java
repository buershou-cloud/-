package com.example.payments.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

@Service
public class AdminAuthService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final Path authFile;
    private final String defaultUsername;
    private final String defaultPassword;
    private final SecureRandom secureRandom = new SecureRandom();

    private Credential credential;

    public AdminAuthService(
            @Value("${payment.admin.auth-file:data/admin-auth.properties}") String authFile,
            @Value("${payment.admin.default-username:admin}") String defaultUsername,
            @Value("${payment.admin.default-password:admin123}") String defaultPassword
    ) {
        this.authFile = Path.of(authFile);
        this.defaultUsername = defaultUsername;
        this.defaultPassword = defaultPassword;
    }

    @PostConstruct
    void load() {
        if (Files.exists(authFile)) {
            credential = readCredential();
            return;
        }
        credential = createCredential(defaultUsername, defaultPassword);
        saveCredential();
    }

    public synchronized String username() {
        return credential.username();
    }

    public synchronized boolean verify(String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            return false;
        }
        String expected = credential.passwordHash();
        String actual = hash(password, credential.salt());
        return credential.username().equals(username.trim()) && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    public synchronized void changePassword(String oldPassword, String newPassword) {
        if (!verify(credential.username(), oldPassword)) {
            throw new IllegalArgumentException("原密码不正确");
        }
        if (isBlank(newPassword) || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新密码至少需要 6 位");
        }
        credential = createCredential(credential.username(), newPassword);
        saveCredential();
    }

    private Credential readCredential() {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(authFile)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("读取后台登录配置失败：" + ex.getMessage(), ex);
        }

        String username = properties.getProperty("username", defaultUsername).trim();
        String salt = properties.getProperty("salt", "");
        String passwordHash = properties.getProperty("passwordHash", "");
        if (username.isBlank() || salt.isBlank() || passwordHash.isBlank()) {
            throw new IllegalStateException("后台登录配置不完整，请删除 " + authFile + " 后重启系统重新生成");
        }
        return new Credential(username, salt, passwordHash);
    }

    private void saveCredential() {
        Properties properties = new Properties();
        properties.setProperty("username", credential.username());
        properties.setProperty("salt", credential.salt());
        properties.setProperty("passwordHash", credential.passwordHash());
        try {
            Path parent = authFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(authFile)) {
                properties.store(output, "Payment gateway admin login");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("保存后台登录配置失败：" + ex.getMessage(), ex);
        }
    }

    private Credential createCredential(String username, String password) {
        String salt = newSalt();
        return new Credential(username.trim(), salt, hash(password, salt));
    }

    private String newSalt() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] bytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", ex);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Credential(String username, String salt, String passwordHash) {
    }
}
