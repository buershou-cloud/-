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
        AdminAuthService service = service(authFile);
        service.load();

        assertThat(service.verify("admin", "admin123")).isTrue();

        service.changePassword("admin", "admin123", "newpass123");
        assertThat(service.verify("admin", "admin123")).isFalse();
        assertThat(service.verify("admin", "newpass123")).isTrue();

        AdminAuthService reloaded = service(authFile);
        reloaded.load();
        assertThat(reloaded.verify("admin", "newpass123")).isTrue();
    }

    @Test
    void addsIndependentAdministratorAndPersistsAllAccounts() {
        Path authFile = tempDir.resolve("multi-admin.properties");
        AdminAuthService service = service(authFile);
        service.load();

        service.addAdministrator("admin", "admin123", "finance.admin", "finance123");

        assertThat(service.usernames()).containsExactly("admin", "finance.admin");
        assertThat(service.verify("admin", "admin123")).isTrue();
        assertThat(service.verify("finance.admin", "finance123")).isTrue();

        AdminAuthService reloaded = service(authFile);
        reloaded.load();
        assertThat(reloaded.usernames()).containsExactly("admin", "finance.admin");
        assertThat(reloaded.verify("finance.admin", "finance123")).isTrue();
    }

    @Test
    void preservesGlobalPaymentPasswordWhenLoginPasswordChanges() {
        Path authFile = tempDir.resolve("payment-password.properties");
        AdminAuthService service = service(authFile);
        service.load();

        service.changePaymentPassword("admin", "admin123", null, "paypass123");
        service.changePassword("admin", "admin123", "newpass123");

        assertThat(service.paymentPasswordConfigured()).isTrue();
        assertThat(service.verifyPaymentPassword("paypass123")).isTrue();
        assertThat(service.verifyPaymentPassword("wrong-password")).isFalse();

        AdminAuthService reloaded = service(authFile);
        reloaded.load();
        assertThat(reloaded.verifyPaymentPassword("paypass123")).isTrue();
    }

    @Test
    void rejectsWrongCurrentPasswordWhenAddingAdministrator() {
        AdminAuthService service = service(tempDir.resolve("auth.properties"));
        service.load();

        assertThatThrownBy(() -> service.addAdministrator("admin", "bad", "finance", "finance123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前登录密码不正确");
    }

    @Test
    void rejectsDuplicateAdministrator() {
        AdminAuthService service = service(tempDir.resolve("duplicate.properties"));
        service.load();

        assertThatThrownBy(() -> service.addAdministrator("admin", "admin123", "admin", "another123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("管理员账号已存在");
    }

    @Test
    void removesAnotherAdministratorButNeverTheCurrentAccount() {
        AdminAuthService service = service(tempDir.resolve("remove.properties"));
        service.load();
        service.addAdministrator("admin", "admin123", "finance", "finance123");

        assertThatThrownBy(() -> service.removeAdministrator("admin", "admin123", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能删除当前登录");

        service.removeAdministrator("admin", "admin123", "finance");
        assertThat(service.usernames()).containsExactly("admin");
        assertThat(service.verify("finance", "finance123")).isFalse();
    }

    private static AdminAuthService service(Path authFile) {
        return new AdminAuthService(authFile.toString(), "admin", "admin123");
    }
}
