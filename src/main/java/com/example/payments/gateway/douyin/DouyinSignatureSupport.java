package com.example.payments.gateway.douyin;

import com.example.payments.gateway.GatewayException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

public final class DouyinSignatureSupport {

    private DouyinSignatureSupport() {
    }

    public static String sign(String message, String merchantPrivateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(merchantPrivateKey));
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_SIGN_ERROR", "Failed to sign Douyin Pay request", ex);
        }
    }

    public static boolean verify(String message, String signatureText, String platformCertificate) {
        if (!hasText(signatureText) || !hasText(platformCertificate)) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(certificate(platformCertificate).getPublicKey());
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureText));
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_VERIFY_ERROR", "Failed to verify Douyin Pay signature", ex);
        }
    }

    public static String certificateSerial(String certificateContent) {
        try {
            return certificate(certificateContent).getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_CERTIFICATE_ERROR", "Failed to parse Douyin Pay certificate", ex);
        }
    }

    public static boolean privateKeyMatchesCertificate(String merchantPrivateKey, String merchantCertificate) {
        String challenge = "douyin-merchant-certificate-check";
        return verify(challenge, sign(challenge, merchantPrivateKey), merchantCertificate);
    }

    public static String decrypt(
            String associatedData,
            String nonce,
            String ciphertext,
            String encryptKey
    ) {
        if (!hasText(encryptKey) || encryptKey.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new GatewayException("DOUYIN_ENCRYPT_KEY_INVALID", "Douyin Pay encrypt key must be exactly 32 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec key = new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_DECRYPT_ERROR", "Failed to decrypt Douyin Pay notification", ex);
        }
    }

    public static String encryptSensitive(String value, String platformCertificate) {
        if (!hasText(value)) {
            return value;
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, certificate(platformCertificate).getPublicKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new GatewayException(
                    "DOUYIN_SENSITIVE_ENCRYPT_ERROR",
                    "Failed to encrypt Douyin Pay sensitive field",
                    ex
            );
        }
    }

    private static PrivateKey privateKey(String content) {
        if (!hasText(content)) {
            throw new GatewayException("DOUYIN_PRIVATE_KEY_MISSING", "Douyin Pay merchant private key is required");
        }
        try {
            String normalized = content
                    .replace("\\r", "")
                    .replace("\\n", "\n")
                    .replace("\uFEFF", "")
                    .replace("\u200B", "")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            try {
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
            } catch (InvalidKeySpecException ignored) {
                return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1AsPkcs8(bytes)));
            }
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_PRIVATE_KEY_INVALID", "Failed to parse Douyin Pay merchant private key", ex);
        }
    }

    private static byte[] wrapPkcs1AsPkcs8(byte[] pkcs1) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] rsaAlgorithm = new byte[]{
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        byte[] privateKey = derValue(0x04, pkcs1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(version);
        body.writeBytes(rsaAlgorithm);
        body.writeBytes(privateKey);
        return derValue(0x30, body.toByteArray());
    }

    private static byte[] derValue(int tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        if (value.length < 128) {
            output.write(value.length);
        } else {
            int lengthBytes = 0;
            for (int length = value.length; length > 0; length >>>= 8) {
                lengthBytes++;
            }
            output.write(0x80 | lengthBytes);
            for (int shift = (lengthBytes - 1) * 8; shift >= 0; shift -= 8) {
                output.write((value.length >>> shift) & 0xff);
            }
        }
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static X509Certificate certificate(String content) {
        if (!hasText(content)) {
            throw new GatewayException("DOUYIN_CERTIFICATE_MISSING", "Douyin Pay certificate is required");
        }
        try {
            byte[] bytes = content.contains("-----BEGIN CERTIFICATE-----")
                    ? content.getBytes(StandardCharsets.UTF_8)
                    : Base64.getMimeDecoder().decode(content.replaceAll("\\s", ""));
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_CERTIFICATE_INVALID", "Failed to parse Douyin Pay certificate", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
