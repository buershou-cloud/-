package com.example.payments.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultCredentialAndPersistsChangedPassword() {
        Path authFile = tempDir.resolve("admin-auth.properties");
        AdminAuthService service = new AdminAuthService(authFile.toString(), "admin", "admin123");
        service.load();

        assertThat(service.verify("admin", "admin123")).isTrue();

        service.changePassword("admin123", "newpass123");
        assertThat(service.verify("admin", "admin123")).isFalse();
        assertThat(service.verify("admin", "newpass123")).isTrue();

        AdminAuthService reloaded = new AdminAuthService(authFile.toString(), "admin", "admin123");
        reloaded.load();
        assertThat(reloaded.verify("admin", "newpass123")).isTrue();
    }

    @Test
    void rejectsWrongOldPassword() {
        AdminAuthService service = new AdminAuthService(tempDir.resolve("auth.properties").toString(), "admin", "admin123");
        service.load();

        assertThatThrownBy(() -> service.changePassword("bad", "newpass123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原密码不正确");
    }
}
