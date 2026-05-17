package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AlipayCertificateSupport {

    static final String PUBLIC_KEY_MODE = "PUBLIC_KEY";
    static final String CERTIFICATE_MODE = "CERTIFICATE";
    private static final Pattern PEM_CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            Pattern.DOTALL
    );

    private AlipayCertificateSupport() {
    }

    static boolean certificateMode(PaymentGatewayProperties.Alipay alipay) {
        return CERTIFICATE_MODE.equalsIgnoreCase(firstText(alipay.getCredentialMode(), PUBLIC_KEY_MODE));
    }

    static String appCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(alipay.getAppCertSn(), certificateSn(alipay.getAppCertContent()).orElse(null));
    }

    static String alipayRootCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(alipay.getAlipayRootCertSn(), rootCertificateSn(alipay.getAlipayRootCertContent()).orElse(null));
    }

    static String alipayPublicKey(PaymentGatewayProperties.Alipay alipay) {
        if (!certificateMode(alipay) || isBlank(alipay.getAlipayCertContent())) {
            return alipay.getAlipayPublicKey();
        }
        return publicKeyFromCertificate(alipay.getAlipayCertContent()).orElse(alipay.getAlipayPublicKey());
    }

    static Optional<String> certificateSn(String certificateContent) {
        return firstCertificate(certificateContent).map(AlipayCertificateSupport::certificateSn);
    }

    static Optional<String> publicKeyFromCertificate(String certificateContent) {
        return firstCertificate(certificateContent)
                .map(certificate -> Base64.getEncoder().encodeToString(certificate.getPublicKey().getEncoded()));
    }

    private static Optional<String> rootCertificateSn(String certificateContent) {
        List<X509Certificate> certificates = certificates(certificateContent);
        String value = certificates.stream()
                .filter(certificate -> certificate.getSigAlgName() != null)
                .filter(certificate -> certificate.getSigAlgName().toUpperCase().contains("RSA"))
                .map(AlipayCertificateSupport::certificateSn)
                .reduce((left, right) -> left + "_" + right)
                .orElse(null);
        return Optional.ofNullable(value);
    }

    private static Optional<X509Certificate> firstCertificate(String certificateContent) {
        List<X509Certificate> certificates = certificates(certificateContent);
        return certificates.isEmpty() ? Optional.empty() : Optional.of(certificates.get(0));
    }

    private static List<X509Certificate> certificates(String certificateContent) {
        if (isBlank(certificateContent)) {
            return List.of();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certificates = new ArrayList<>();
            Matcher matcher = PEM_CERTIFICATE.matcher(certificateContent);
            while (matcher.find()) {
                byte[] bytes = Base64.getMimeDecoder().decode(matcher.group(1));
                certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes)));
            }
            if (!certificates.isEmpty()) {
                return certificates;
            }

            byte[] raw = Base64.getMimeDecoder().decode(certificateContent.replaceAll("\\s", ""));
            Collection<? extends java.security.cert.Certificate> generated =
                    factory.generateCertificates(new ByteArrayInputStream(raw));
            for (java.security.cert.Certificate certificate : generated) {
                if (certificate instanceof X509Certificate x509Certificate) {
                    certificates.add(x509Certificate);
                }
            }
            return certificates;
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_CERTIFICATE_ERROR", "Failed to parse Alipay certificate content", ex);
        }
    }

    private static String certificateSn(X509Certificate certificate) {
        try {
            String source = certificate.getIssuerX500Principal().getName() + certificate.getSerialNumber();
            byte[] digest = MessageDigest.getInstance("MD5").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_CERTIFICATE_SN_ERROR", "Failed to calculate Alipay certificate SN", ex);
        }
    }

    private static String firstText(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
