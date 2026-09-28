package com.example.payments.merchant;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

/** Shared, persistent gateway signing identity. Existing invalid keys fail startup rather than rotate. */
final class PlatformRsaKeys {
    private PlatformRsaKeys() { }

    static synchronized Material load(Path requestedPath) {
        Path path = requestedPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(path.getParent());
            Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                if (!Files.exists(path)) {
                    create(path);
                }
                Properties properties = new Properties();
                try (InputStream input = Files.newInputStream(path)) {
                    properties.load(input);
                }
                Material result = new Material(properties.getProperty("publicKey"), properties.getProperty("privateKey"));
                validate(result);
                return result;
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot load platform signing keys from " + path + "; preserve the key file and check its configuration", ex);
        }
    }

    private static void create(Path path) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        Properties properties = new Properties();
        properties.setProperty("publicKey", pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        properties.setProperty("privateKey", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Path temporary = Files.getFileStore(path.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)
                ? Files.createTempFile(path.getParent(), ".platform-key-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(path.getParent(), ".platform-key-", ".tmp");
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (acl != null) {
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            }
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Payment gateway platform signing identity - keep private and back up");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validate(Material keys) throws GeneralSecurityException {
        if (keys.publicKey() == null || keys.privateKey() == null) {
            throw new IllegalArgumentException("Both platform publicKey and privateKey are required");
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        var publicKey = factory.generatePublic(new X509EncodedKeySpec(decode(keys.publicKey(), "PUBLIC KEY")));
        var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(decode(keys.privateKey(), "PRIVATE KEY")));
        byte[] probe = "payment-gateway-platform-key-check".getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(probe);
        byte[] signed = signature.sign();
        signature.initVerify(publicKey);
        signature.update(probe);
        if (!signature.verify(signed)) {
            throw new IllegalArgumentException("Platform public and private keys do not match");
        }
    }

    private static byte[] decode(String pem, String type) {
        return Base64.getDecoder().decode(pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "").replaceAll("\\s", ""));
    }

    private static String pem(String type, byte[] content) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(content)
                + "\n-----END " + type + "-----";
    }

    record Material(String publicKey, String privateKey) { }
}
