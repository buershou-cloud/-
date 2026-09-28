package com.example.payments.merchant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRsaKeysTest {
    @TempDir Path directory;

    @Test
    void restartKeepsTheSamePlatformPrivateAndPublicKeys() {
        Path file = directory.resolve("platform.properties");
        DemoMerchantService first = new DemoMerchantService(null, file);
        DemoMerchantView original = first.create(new DemoMerchantCreateRequest("M1", "Merchant", null, null, null, null, null));
        DemoMerchantService restarted = new DemoMerchantService(null, file);
        DemoMerchantView afterRestart = restarted.create(new DemoMerchantCreateRequest("M2", "Merchant", null, null, null, null, null));

        assertThat(restarted.platformPrivateKey()).isEqualTo(first.platformPrivateKey());
        assertThat(afterRestart.platformPublicKey()).isEqualTo(original.platformPublicKey());
        assertThat(afterRestart.status()).isEqualTo("正常");
    }

    @Test
    void concurrentInitializersUseOneSharedKeyFile() {
        Path file = directory.resolve("shared/platform.properties");
        var first = CompletableFuture.supplyAsync(() -> PlatformRsaKeys.load(file));
        var second = CompletableFuture.supplyAsync(() -> PlatformRsaKeys.load(file));

        assertThat(first.join()).isEqualTo(second.join());
        assertThat(PlatformRsaKeys.load(file)).isEqualTo(first.join());
    }

    @Test
    void mismatchedExistingKeysFailWithoutSilentlyReplacingThem() throws Exception {
        Path first = directory.resolve("one.properties");
        var original = PlatformRsaKeys.load(first);
        var other = PlatformRsaKeys.load(directory.resolve("two.properties"));
        Properties invalid = new Properties();
        invalid.setProperty("publicKey", original.publicKey());
        invalid.setProperty("privateKey", other.privateKey());
        try (var output = Files.newOutputStream(first)) {
            invalid.store(output, "mismatched test keys");
        }
        byte[] before = Files.readAllBytes(first);

        assertThatThrownBy(() -> PlatformRsaKeys.load(first)).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Platform public and private keys do not match");
        assertThat(Files.readAllBytes(first)).isEqualTo(before);
    }

    @Test
    void incompleteExistingFileFailsWithoutGeneratingAnotherIdentity() throws Exception {
        Path file = directory.resolve("broken.properties");
        Files.writeString(file, "publicKey=invalid");

        assertThatThrownBy(() -> PlatformRsaKeys.load(file)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(file)).isEqualTo("publicKey=invalid");
    }
}
