package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AlipayCertificateSupport {

    static final String PUBLIC_KEY_MODE = "PUBLIC_KEY";
    static final String CERTIFICATE_MODE = "CERTIFICATE";
    private static final Pattern PEM_CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            Pattern.DOTALL
    );
    private static final String APP_CERT_FILE = "appCertPublicKey.crt";
    private static final String APP_CERT_FILE_PATTERN = "appCertPublicKey_*.crt";
    private static final String ALIPAY_CERT_FILE = "alipayCertPublicKey_RSA2.crt";
    private static final String ALIPAY_ROOT_CERT_FILE = "alipayRootCert.crt";

    private AlipayCertificateSupport() {
    }

    static boolean certificateMode(PaymentGatewayProperties.Alipay alipay) {
        return CERTIFICATE_MODE.equalsIgnoreCase(firstText(alipay.getCredentialMode(), PUBLIC_KEY_MODE));
    }

    static boolean certificateMode(PaymentGatewayProperties.Channel channel) {
        return certificateMode(channel.getAlipay());
    }

    static String appCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(alipay.getAppCertSn(), certificateSn(alipay.getAppCertContent()).orElse(null));
    }

    static String appCertSn(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        return firstText(alipay.getAppCertSn(), certificateSn(appCertContent(channel)).orElse(null));
    }

    static String alipayRootCertSn(PaymentGatewayProperties.Alipay alipay) {
        return firstText(alipay.getAlipayRootCertSn(), rootCertificateSn(alipay.getAlipayRootCertContent()).orElse(null));
    }

    static String alipayRootCertSn(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        return firstText(alipay.getAlipayRootCertSn(), rootCertificateSn(alipayRootCertContent(channel)).orElse(null));
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
        return publicKeyFromCertificate(alipayCertContent(channel)).orElse(alipay.getAlipayPublicKey());
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

    private static String appCertContent(PaymentGatewayProperties.Channel channel) {
        return firstValidCertificateContent(
                channel.getAlipay().getAppCertContent(),
                certFile(channel, APP_CERT_FILE, APP_CERT_FILE_PATTERN)
        );
    }

    private static String alipayCertContent(PaymentGatewayProperties.Channel channel) {
        return firstValidCertificateContent(
                channel.getAlipay().getAlipayCertContent(),
                certFile(channel, ALIPAY_CERT_FILE)
        );
    }

    private static String alipayRootCertContent(PaymentGatewayProperties.Channel channel) {
        return firstValidCertificateContent(
                channel.getAlipay().getAlipayRootCertContent(),
                certFile(channel, ALIPAY_ROOT_CERT_FILE)
        );
    }

    private static String firstValidCertificateContent(String preferred, String fallback) {
        if (isBlank(preferred)) {
            return fallback;
        }
        if (canParseCertificateContent(preferred)) {
            return preferred.trim();
        }
        if (!isBlank(fallback) && canParseCertificateContent(fallback)) {
            return fallback;
        }
        return preferred.trim();
    }

    private static boolean canParseCertificateContent(String value) {
        try {
            return !certificates(value).isEmpty();
        } catch (GatewayException ex) {
            return false;
        }
    }

    private static String certFile(PaymentGatewayProperties.Channel channel, String fileName, String... fallbackPatterns) {
        for (Path root : certRoots()) {
            Path path = root.resolve(channel.getId()).resolve(fileName);
            if (Files.isRegularFile(path)) {
                return readString(path);
            }
        }
        for (String pattern : fallbackPatterns) {
            for (Path root : certRoots()) {
                Path directory = root.resolve(channel.getId());
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                List<Path> matches = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, pattern)) {
                    for (Path path : stream) {
                        if (Files.isRegularFile(path)) {
                            matches.add(path);
                        }
                    }
                } catch (IOException ex) {
                    throw new GatewayException("ALIPAY_CERTIFICATE_FILE_ERROR", "Failed to list Alipay certificate directory: " + directory, ex);
                }
                matches.sort(Comparator.comparing(path -> path.getFileName().toString()));
                if (!matches.isEmpty()) {
                    return readString(matches.get(0));
                }
            }
        }
        return null;
    }

    private static Set<Path> certRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        addRoot(roots, System.getProperty("payment.cert.dir"));
        addRoot(roots, System.getenv("PAYMENT_CERT_DIR"));
        addRoot(roots, "certs");
        addRoot(roots, Paths.get(System.getProperty("user.dir", ".")).resolve("certs").toString());
        return roots;
    }

    private static void addRoot(Set<Path> roots, String value) {
        if (isBlank(value)) {
            return;
        }
        Path path = Paths.get(value.trim());
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir", ".")).resolve(path);
        }
        roots.add(path.normalize().toAbsolutePath());
    }

    private static String readString(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 0 && bytes[0] == 0x30) {
                return Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes);
            }
            return new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        } catch (IOException ex) {
            throw new GatewayException("ALIPAY_CERTIFICATE_FILE_ERROR", "Failed to read Alipay certificate file: " + path, ex);
        }
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
