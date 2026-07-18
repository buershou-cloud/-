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
        assertThat(service.isSuperAdministrator("admin")).isTrue();

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
        assertThat(reloaded.isSuperAdministrator("admin")).isTrue();
        assertThat(reloaded.isSuperAdministrator("finance.admin")).isFalse();
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
    void removesAnotherAdministratorButNeverTheSuperAdministrator() {
        AdminAuthService service = service(tempDir.resolve("remove.properties"));
        service.load();
        service.addAdministrator("admin", "admin123", "finance", "finance123");

        assertThatThrownBy(() -> service.removeAdministrator("admin", "admin123", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超级管理员账号不可删除");

        service.removeAdministrator("admin", "admin123", "finance");
        assertThat(service.usernames()).containsExactly("admin");
        assertThat(service.verify("finance", "finance123")).isFalse();
    }

    @Test
    void ordinaryAdministratorCanOnlyMaintainOwnLoginPassword() {
        AdminAuthService service = service(tempDir.resolve("roles.properties"));
        service.load();
        service.addAdministrator("admin", "admin123", "finance", "finance123");

        service.changePassword("finance", "finance123", "finance456");
        assertThat(service.verify("finance", "finance456")).isTrue();

        assertThatThrownBy(() -> service.addAdministrator("finance", "finance456", "operator", "operator123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有超级管理员");
        assertThatThrownBy(() -> service.removeAdministrator("finance", "finance456", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有超级管理员");
        assertThatThrownBy(() -> service.changePaymentPassword("finance", "finance456", null, "paypass123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只有超级管理员");
        assertThatThrownBy(() -> service.changeUsername("finance", "finance456", "finance.new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("创建后不可修改");
    }

    private static AdminAuthService service(Path authFile) {
        return new AdminAuthService(authFile.toString(), "admin", "admin123");
    }
}
