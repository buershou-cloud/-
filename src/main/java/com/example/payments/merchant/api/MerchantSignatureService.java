package com.example.payments.merchant.api;

import com.example.payments.gateway.GatewayException;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class MerchantSignatureService {

    private static final Duration TIMESTAMP_SKEW = Duration.ofMinutes(15);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DemoMerchantService merchantService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Instant> usedNonces = new ConcurrentHashMap<>();

    public MerchantSignatureService(DemoMerchantService merchantService, ObjectMapper objectMapper) {
        this.merchantService = merchantService;
        this.objectMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public DemoMerchantView verify(MerchantSignedRequest request) {
        DemoMerchantView merchant = merchantService.detail(required(request.merchantId(), "merchantId is required"));
        String signType = normalizeSignType(request.signType());
        if (!signTypeAllowed(merchant.signMode(), signType)) {
            throw new IllegalArgumentException("Sign type is not enabled for merchant: " + signType);
        }
        Instant timestamp = parseTimestamp(required(request.timestamp(), "timestamp is required"));
        ensureFreshTimestamp(timestamp);

        Map<String, Object> values = objectMapper.convertValue(request, MAP_TYPE);
        String content = canonicalize(values);
        String sign = required(request.sign(), "sign is required");
        boolean verified = switch (signType) {
            case "MD5" -> md5(content + merchant.md5Key()).equalsIgnoreCase(sign);
            case "RSA2" -> rsa2Verify(content, sign, merchant.rsa2PublicKey());
            default -> false;
        };
        if (!verified) {
            throw new IllegalArgumentException("Merchant API signature verification failed");
        }
        rememberNonce(merchant.merchantId(), required(request.nonce(), "nonce is required"), timestamp);
        return merchant;
    }

    public <T> MerchantApiResponse<T> success(DemoMerchantView merchant, String signType, T data) {
        return response(merchant, signType, "SUCCESS", "OK", data);
    }

    public <T> MerchantApiResponse<T> response(
            DemoMerchantView merchant,
            String signType,
            String code,
            String message,
            T data
    ) {
        String normalizedSignType = normalizeSignType(signType);
        String timestamp = Instant.now().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("data", data);
        payload.put("timestamp", timestamp);
        payload.put("signType", normalizedSignType);
        return new MerchantApiResponse<>(
                code,
                message,
                data,
                timestamp,
                normalizedSignType,
                signForMerchant(merchant, normalizedSignType, payload)
        );
    }

    public String signForMerchant(DemoMerchantView merchant, String signType, Map<String, Object> payload) {
        String content = canonicalize(payload);
        return switch (normalizeSignType(signType)) {
            case "MD5" -> md5(content + merchant.md5Key());
            case "RSA2" -> rsa2Sign(content, merchantService.platformPrivateKey());
            default -> throw new IllegalArgumentException("Unsupported signType: " + signType);
        };
    }

    public String defaultSignType(DemoMerchantView merchant) {
        String mode = merchant.signMode() == null ? "" : merchant.signMode().trim().toUpperCase();
        return mode.contains("MD5") ? "MD5" : "RSA2";
    }

    private String canonicalize(Map<String, Object> values) {
        Map<String, Object> normalized = normalizeMap(values);
        return normalized.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + valueText(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new TreeMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String name = key.toString();
            if ("sign".equals(name)) {
                return;
            }
            Object normalized = normalizeValue(value);
            if (normalized == null) {
                return;
            }
            if (normalized instanceof String text && text.isBlank()) {
                return;
            }
            result.put(name, normalized);
        });
        return result;
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>();
            for (Object item : collection) {
                Object normalized = normalizeValue(item);
                if (normalized != null) {
                    list.add(normalized);
                }
            }
            return list;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof BigDecimal amount) {
            return amount.toPlainString();
        }
        return value;
    }

    private String valueText(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new GatewayException("MERCHANT_SIGN_SERIALIZE_ERROR", "Failed to serialize merchant sign content", ex);
        }
    }

    private void ensureFreshTimestamp(Instant timestamp) {
        Instant now = Instant.now();
        if (timestamp.isBefore(now.minus(TIMESTAMP_SKEW)) || timestamp.isAfter(now.plus(TIMESTAMP_SKEW))) {
            throw new IllegalArgumentException("timestamp is expired or too far in the future");
        }
    }

    private void rememberNonce(String merchantId, String nonce, Instant timestamp) {
        Instant threshold = Instant.now().minus(TIMESTAMP_SKEW);
        usedNonces.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        String key = merchantId + ":" + nonce;
        if (usedNonces.putIfAbsent(key, timestamp) != null) {
            throw new IllegalArgumentException("nonce has already been used");
        }
    }

    private static String normalizeSignType(String signType) {
        String value = required(signType, "signType is required").trim().toUpperCase();
        if (!"MD5".equals(value) && !"RSA2".equals(value)) {
            throw new IllegalArgumentException("Unsupported signType: " + signType);
        }
        return value;
    }

    private static boolean signTypeAllowed(String signMode, String signType) {
        String mode = signMode == null ? "MD5_RSA2" : signMode.trim().toUpperCase();
        return "MD5_RSA2".equals(mode) || mode.equals(signType);
    }

    private static Instant parseTimestamp(String timestamp) {
        String value = timestamp.trim();
        try {
            if (value.matches("\\d{13}")) {
                return Instant.ofEpochMilli(Long.parseLong(value));
            }
            if (value.matches("\\d{10}")) {
                return Instant.ofEpochSecond(Long.parseLong(value));
            }
            return Instant.parse(value);
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw new IllegalArgumentException("timestamp format is invalid");
        }
    }

    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02X", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new GatewayException("MERCHANT_MD5_ERROR", "Failed to calculate MD5 signature", ex);
        }
    }

    private static String rsa2Sign(String content, String privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(privateKey));
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new GatewayException("MERCHANT_RSA2_SIGN_ERROR", "Failed to sign merchant response", ex);
        }
    }

    private static boolean rsa2Verify(String content, String sign, String publicKey) {
        if (publicKey == null || publicKey.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey(publicKey));
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            throw new GatewayException("MERCHANT_RSA2_VERIFY_ERROR", "Failed to verify merchant RSA2 signature", ex);
        }
    }

    private static PrivateKey privateKey(String privateKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(privateKey));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static PublicKey publicKey(String publicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(publicKey));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private static String cleanPem(String key) {
        return key == null ? "" : key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
