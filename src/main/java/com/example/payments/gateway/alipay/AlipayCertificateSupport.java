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

    static boolean certificateMode(PaymentGatewayProperties.Channel channel) {
        return certificateMode(channel.getAlipay());
    }

    static String appCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(certificateSn(alipay.getAppCertContent()).orElse(null), alipay.getAppCertSn());
    }

    static String appCertSn(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        return firstText(certificateSn(appCertContent(channel), "app certificate").orElse(null), alipay.getAppCertSn());
    }

    static String alipayRootCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(rootCertificateSn(alipay.getAlipayRootCertContent()).orElse(null), alipay.getAlipayRootCertSn());
    }

    static String alipayRootCertSn(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        return firstText(rootCertificateSn(alipayRootCertContent(channel), "Alipay root certificate").orElse(null), alipay.getAlipayRootCertSn());
    }

    static String alipayPublicKey(PaymentGatewayProperties.Alipay alipay) {
        if (!certificateMode(alipay) || isBlank(alipay.getAlipayCertContent())) {
            return alipay.getAlipayPublicKey();
        }
        return publicKeyFromCertificate(alipay.getAlipayCertContent()).orElse(alipay.getAlipayPublicKey());
    }

    static String alipayPublicKey(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        if (!certificateMode(alipay)) {
            return alipay.getAlipayPublicKey();
        }
        return publicKeyFromCertificate(alipayCertContent(channel), "Alipay public certificate").orElse(alipay.getAlipayPublicKey());
    }

    static Optional<String> certificateSn(String certificateContent) {
        return certificateSn(certificateContent, "certificate");
    }

    static Optional<String> certificateSn(String certificateContent, String label) {
        return firstCertificate(certificateContent, label).map(AlipayCertificateSupport::certificateSn);
    }

    static Optional<String> publicKeyFromCertificate(String certificateContent) {
        return publicKeyFromCertificate(certificateContent, "certificate");
    }

    static Optional<String> publicKeyFromCertificate(String certificateContent, String label) {
        return firstCertificate(certificateContent, label)
                .map(certificate -> Base64.getEncoder().encodeToString(certificate.getPublicKey().getEncoded()));
    }

    private static Optional<String> rootCertificateSn(String certificateContent) {
        return rootCertificateSn(certificateContent, "certificate");
    }

    private static Optional<String> rootCertificateSn(String certificateContent, String label) {
        List<X509Certificate> certificates = rootCertificates(certificateContent, label);
        String value = certificates.stream()
                .filter(certificate -> certificate.getSigAlgName() != null)
                .filter(certificate -> certificate.getSigAlgName().toUpperCase().contains("RSA"))
                .map(AlipayCertificateSupport::certificateSn)
                .reduce((left, right) -> left + "_" + right)
                .orElse(null);
        return Optional.ofNullable(value);
    }

    private static Optional<X509Certificate> firstCertificate(String certificateContent, String label) {
        List<X509Certificate> certificates = certificates(certificateContent, label);
        return certificates.isEmpty() ? Optional.empty() : Optional.of(certificates.get(0));
    }

    private static String appCertContent(PaymentGatewayProperties.Channel channel) {
        return channel.getAlipay().getAppCertContent();
    }

    private static String alipayCertContent(PaymentGatewayProperties.Channel channel) {
        return channel.getAlipay().getAlipayCertContent();
    }

    private static String alipayRootCertContent(PaymentGatewayProperties.Channel channel) {
        return channel.getAlipay().getAlipayRootCertContent();
    }

    private static List<X509Certificate> certificates(String certificateContent) {
        return certificates(certificateContent, "certificate");
    }

    private static List<X509Certificate> certificates(String certificateContent, String label) {
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
            throw new GatewayException("ALIPAY_CERTIFICATE_ERROR", "Failed to parse Alipay " + label + " content", ex);
        }
    }

    private static List<X509Certificate> rootCertificates(String certificateContent, String label) {
        if (isBlank(certificateContent)) {
            return List.of();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certificates = new ArrayList<>();
            Matcher matcher = PEM_CERTIFICATE.matcher(certificateContent);
            int pemBlocks = 0;
            Exception firstFailure = null;
            while (matcher.find()) {
                pemBlocks++;
                try {
                    byte[] bytes = Base64.getMimeDecoder().decode(matcher.group(1));
                    certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes)));
                } catch (Exception ex) {
                    if (firstFailure == null) {
                        firstFailure = ex;
                    }
                }
            }
            if (!certificates.isEmpty()) {
                return certificates;
            }
            if (pemBlocks > 0) {
                throw new GatewayException("ALIPAY_CERTIFICATE_ERROR", "Failed to parse Alipay " + label + " content", firstFailure);
            }
            return certificates(certificateContent, label);
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_CERTIFICATE_ERROR", "Failed to parse Alipay " + label + " content", ex);
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
