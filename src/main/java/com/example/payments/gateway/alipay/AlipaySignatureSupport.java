package com.example.payments.gateway.alipay;

import com.example.payments.gateway.GatewayException;

import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

public final class AlipaySignatureSupport {

    private AlipaySignatureSupport() {
    }

    public static String sign(Map<String, String> params, String privateKey, String signType, Charset charset) {
        try {
            Signature signature = Signature.getInstance(algorithm(signType));
            signature.initSign(privateKey(privateKey));
            signature.update(canonicalizeForRequestSign(params).getBytes(charset));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_SIGN_ERROR", "Failed to sign Alipay request", ex);
        }
    }

    public static boolean verify(Map<String, String> params, String alipayPublicKey, String signType, Charset charset) {
        String sign = params.get("sign");
        if (isBlank(sign) || isBlank(alipayPublicKey)) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(algorithm(signType));
            signature.initVerify(publicKey(alipayPublicKey));
            signature.update(canonicalizeForVerify(params).getBytes(charset));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_VERIFY_ERROR", "Failed to verify Alipay signature", ex);
        }
    }

    static String canonicalizeForRequestSign(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> !isBlank(entry.getValue()))
                .filter(entry -> !"sign".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    static String canonicalizeForVerify(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(entry -> !isBlank(entry.getValue()))
                .filter(entry -> !"sign".equals(entry.getKey()))
                .filter(entry -> !"sign_type".equals(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    private static PrivateKey privateKey(String privateKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(privateKey));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    private static PublicKey publicKey(String publicKey) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(cleanPem(publicKey));
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePublic(keySpec);
    }

    private static String algorithm(String signType) {
        return "RSA".equalsIgnoreCase(signType) ? "SHA1withRSA" : "SHA256withRSA";
    }

    private static String cleanPem(String key) {
        if (key == null) {
            return "";
        }
        return key
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
