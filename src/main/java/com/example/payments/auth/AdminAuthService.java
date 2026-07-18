package com.example.payments.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class AdminAuthService {

    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int PAYMENT_PASSWORD_ITERATIONS = 210_000;
    private static final int PAYMENT_PASSWORD_BITS = 256;
    private static final String USERNAME_PATTERN = "[A-Za-z0-9._@-]{3,32}";

    private final Path authFile;
    private final String defaultUsername;
    private final String defaultPassword;
    private final SecureRandom secureRandom = new SecureRandom();

    private LinkedHashMap<String, Credential> credentials;
    private String paymentSalt;
    private String paymentPasswordHash;

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
            readCredentials();
            return;
        }
        credentials = new LinkedHashMap<>();
        Credential credential = createCredential(defaultUsername, defaultPassword);
        credentials.put(credential.username(), credential);
        paymentSalt = "";
        paymentPasswordHash = "";
        saveCredentials();
    }

    public synchronized String username() {
        return credentials.keySet().iterator().next();
    }

    public synchronized List<String> usernames() {
        return List.copyOf(credentials.keySet());
    }

    public synchronized boolean hasUsername(String username) {
        return !isBlank(username) && credentials.containsKey(username.trim());
    }

    public synchronized boolean verify(String username, String password) {
        if (isBlank(username) || isBlank(password)) {
            return false;
        }
        Credential credential = credentials.get(username.trim());
        if (credential == null) {
            return false;
        }
        String actual = hash(password, credential.salt());
        return MessageDigest.isEqual(
                credential.passwordHash().getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    public synchronized void changePassword(String username, String oldPassword, String newPassword) {
        Credential credential = requiredCredential(username);
        if (!verify(credential.username(), oldPassword)) {
            throw new IllegalArgumentException("原密码不正确");
        }
        validatePassword(newPassword, "新密码至少需要 6 位");
        credentials.put(credential.username(), createCredential(credential.username(), newPassword));
        saveCredentials();
    }

    public synchronized void changeUsername(String currentUsername, String currentPassword, String newUsername) {
        Credential current = requiredCredential(currentUsername);
        if (!verify(current.username(), currentPassword)) {
            throw new IllegalArgumentException("登录密码不正确");
        }
        String username = validateUsername(newUsername);
        if (!current.username().equals(username) && credentials.containsKey(username)) {
            throw new IllegalArgumentException("管理员账号已存在");
        }

        LinkedHashMap<String, Credential> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
            if (entry.getKey().equals(current.username())) {
                renamed.put(username, new Credential(username, current.salt(), current.passwordHash()));
            } else {
                renamed.put(entry.getKey(), entry.getValue());
            }
        }
        credentials = renamed;
        saveCredentials();
    }

    public synchronized void addAdministrator(
            String currentUsername,
            String currentPassword,
            String newUsername,
            String newPassword
    ) {
        Credential current = requiredCredential(currentUsername);
        if (!verify(current.username(), currentPassword)) {
            throw new IllegalArgumentException("当前登录密码不正确");
        }
        String username = validateUsername(newUsername);
        if (credentials.containsKey(username)) {
            throw new IllegalArgumentException("管理员账号已存在");
        }
        validatePassword(newPassword, "新管理员密码至少需要 6 位");
        Credential credential = createCredential(username, newPassword);
        credentials.put(username, credential);
        saveCredentials();
    }

    public synchronized void removeAdministrator(
            String currentUsername,
            String currentPassword,
            String targetUsername
    ) {
        Credential current = requiredCredential(currentUsername);
        if (!verify(current.username(), currentPassword)) {
            throw new IllegalArgumentException("当前登录密码不正确");
        }
        String target = targetUsername == null ? "" : targetUsername.trim();
        if (current.username().equals(target)) {
            throw new IllegalArgumentException("不能删除当前登录的管理员账号");
        }
        if (!credentials.containsKey(target)) {
            throw new IllegalArgumentException("要删除的管理员账号不存在");
        }
        if (credentials.size() <= 1) {
            throw new IllegalArgumentException("系统至少需要保留一个管理员账号");
        }
        credentials.remove(target);
        saveCredentials();
    }

    public synchronized boolean paymentPasswordConfigured() {
        return !isBlank(paymentSalt) && !isBlank(paymentPasswordHash);
    }

    public synchronized boolean verifyPaymentPassword(String paymentPassword) {
        if (!paymentPasswordConfigured() || isBlank(paymentPassword)) {
            return false;
        }
        String actual = paymentPasswordHash(paymentPassword, paymentSalt);
        return MessageDigest.isEqual(
                paymentPasswordHash.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    public synchronized void changePaymentPassword(
            String username,
            String adminPassword,
            String currentPaymentPassword,
            String newPaymentPassword
    ) {
        Credential credential = requiredCredential(username);
        if (!verify(credential.username(), adminPassword)) {
            throw new IllegalArgumentException("登录密码不正确");
        }
        if (paymentPasswordConfigured() && !verifyPaymentPassword(currentPaymentPassword)) {
            throw new IllegalArgumentException("原支付密码不正确");
        }
        validatePassword(newPaymentPassword, "新支付密码至少需要 6 位");
        paymentSalt = newSalt();
        paymentPasswordHash = paymentPasswordHash(newPaymentPassword, paymentSalt);
        saveCredentials();
    }

    private void readCredentials() {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(authFile)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("读取后台登录配置失败：" + ex.getMessage(), ex);
        }

        LinkedHashMap<String, Credential> loaded = new LinkedHashMap<>();
        int count = parseAdminCount(properties.getProperty("admin.count"));
        if (count > 0) {
            for (int index = 0; index < count; index++) {
                String prefix = "admin." + index + ".";
                Credential credential = credentialFrom(
                        properties.getProperty(prefix + "username", ""),
                        properties.getProperty(prefix + "salt", ""),
                        properties.getProperty(prefix + "passwordHash", "")
                );
                loaded.put(credential.username(), credential);
            }
        } else {
            Credential legacy = credentialFrom(
                    properties.getProperty("username", defaultUsername),
                    properties.getProperty("salt", ""),
                    properties.getProperty("passwordHash", "")
            );
            loaded.put(legacy.username(), legacy);
        }
        credentials = loaded;
        paymentSalt = properties.getProperty("paymentSalt", "");
        paymentPasswordHash = properties.getProperty("paymentPasswordHash", "");
    }

    private void saveCredentials() {
        Properties properties = new Properties();
        List<Credential> values = new ArrayList<>(credentials.values());
        Credential primary = values.getFirst();

        // Keep the legacy keys so an older deployment can still read the primary account.
        properties.setProperty("username", primary.username());
        properties.setProperty("salt", primary.salt());
        properties.setProperty("passwordHash", primary.passwordHash());
        properties.setProperty("admin.count", Integer.toString(values.size()));
        for (int index = 0; index < values.size(); index++) {
            Credential credential = values.get(index);
            String prefix = "admin." + index + ".";
            properties.setProperty(prefix + "username", credential.username());
            properties.setProperty(prefix + "salt", credential.salt());
            properties.setProperty(prefix + "passwordHash", credential.passwordHash());
        }
        if (paymentPasswordConfigured()) {
            properties.setProperty("paymentSalt", paymentSalt);
            properties.setProperty("paymentPasswordHash", paymentPasswordHash);
        }

        try {
            Path parent = authFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream output = Files.newOutputStream(authFile)) {
                properties.store(output, "Payment gateway administrator accounts");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("保存后台登录配置失败：" + ex.getMessage(), ex);
        }
    }

    private Credential requiredCredential(String username) {
        Credential credential = isBlank(username) ? null : credentials.get(username.trim());
        if (credential == null) {
            throw new IllegalArgumentException("管理员账号不存在或登录已失效");
        }
        return credential;
    }

    private Credential createCredential(String username, String password) {
        String cleanUsername = validateUsername(username);
        String salt = newSalt();
        return new Credential(cleanUsername, salt, hash(password, salt));
    }

    private static Credential credentialFrom(String username, String salt, String passwordHash) {
        String cleanUsername = username == null ? "" : username.trim();
        if (!cleanUsername.matches(USERNAME_PATTERN) || isBlank(salt) || isBlank(passwordHash)) {
            throw new IllegalStateException("后台登录配置不完整，请检查管理员账号配置文件");
        }
        return new Credential(cleanUsername, salt, passwordHash);
    }

    private static int parseAdminCount(String value) {
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("后台管理员账号数量配置无效", ex);
        }
    }

    private static String validateUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!username.matches(USERNAME_PATTERN)) {
            throw new IllegalArgumentException("管理员账号必须为 3-32 位字母、数字或 . _ @ -");
        }
        return username;
    }

    private static void validatePassword(String value, String message) {
        if (isBlank(value) || value.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(message);
        }
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

    private static String paymentPasswordHash(String password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                Base64.getDecoder().decode(salt),
                PAYMENT_PASSWORD_ITERATIONS,
                PAYMENT_PASSWORD_BITS
        );
        try {
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return HexFormat.of().formatHex(bytes);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("当前 Java 环境不支持支付密码加密", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Credential(String username, String salt, String passwordHash) {
    }
}
