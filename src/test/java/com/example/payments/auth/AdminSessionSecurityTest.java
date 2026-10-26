package com.example.payments.auth;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.web.AdminAuthController;
import com.example.payments.web.AdminAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSessionSecurityTest {
    @TempDir Path directory;
    private AdminAuthService auth;
    private AdminAuthController controller;

    @BeforeEach
    void setup() {
        auth = service();
        GraphicVerificationService graphic = mock(GraphicVerificationService.class);
        when(graphic.verify(any(), anyString(), anyInt())).thenReturn(true);
        controller = new AdminAuthController(auth, graphic);
    }

    @Test
    void successfulLoginRotatesPreAuthenticationIdAndDoesNotStorePasswordMaterial() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String before = request.getSession().getId();
        login(request, "admin123");
        assertThat(request.getSession().getId()).isNotEqualTo(before);
        assertThat(controller.session(request).authenticated()).isTrue();
        assertThat(request.getSession().getAttribute(AdminSession.CREDENTIAL_ATTRIBUTE))
                .isInstanceOf(String.class).isNotEqualTo("admin123");
        assertThat(java.util.Collections.list(request.getSession().getAttributeNames()))
                .containsExactlyInAnyOrder(AdminSession.USERNAME_ATTRIBUTE, AdminSession.CREDENTIAL_ATTRIBUTE);
    }

    @Test
    void passwordChangeKeepsTheChangingAdministratorLoggedInAndRejectsAllOldSessions() throws Exception {
        MockHttpServletRequest current = new MockHttpServletRequest();
        MockHttpServletRequest old = new MockHttpServletRequest("GET", "/api/v1/orders/recent");
        login(current, "admin123");
        login(old, "admin123");
        String before = current.getSession().getId();
        String oldFingerprint = (String) old.getSession().getAttribute(AdminSession.CREDENTIAL_ATTRIBUTE);

        assertThat(controller.changePassword(new AdminPasswordChangeRequest("admin123", "new-password"), current).authenticated()).isTrue();
        assertThat(current.getSession().getId()).isNotEqualTo(before);
        assertThat(controller.session(current).authenticated()).isTrue();
        assertThat(auth.matchesSession("admin", oldFingerprint)).isFalse();
        assertThat(new AdminAuthInterceptor(auth, mock(DemoMerchantService.class))
                .preHandle(old, new MockHttpServletResponse(), new Object())).isFalse();
        assertThat(controller.session(old).authenticated()).isFalse();
        assertThatThrownBy(() -> controller.security(old)).isInstanceOf(IllegalStateException.class);

        AdminAuthService restarted = service();
        assertThat(restarted.matchesSession("admin", oldFingerprint)).isFalse();
        assertThat(AdminSession.isAuthenticated(current, restarted)).isTrue();
    }

    @Test
    void passwordChangedBackAndDeletedThenRecreatedAdministratorDoNotResurrectSessions() {
        String old = auth.authenticateSession("admin", "admin123");
        auth.changePassword("admin", "admin123", "new-password");
        auth.changePassword("admin", "new-password", "admin123");
        assertThat(auth.matchesSession("admin", old)).isFalse();
        auth.addAdministrator("admin", "admin123", "operator", "operator123");
        String deleted = auth.authenticateSession("operator", "operator123");
        auth.removeAdministrator("admin", "admin123", "operator");
        auth.addAdministrator("admin", "admin123", "operator", "operator123");
        assertThat(auth.matchesSession("operator", deleted)).isFalse();
    }

    @Test
    void usernameOnlySessionAndWrongPasswordDoNotAuthenticate() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AdminSession.USERNAME_ATTRIBUTE, "admin");
        assertThat(controller.session(request).authenticated()).isFalse();
        String before = request.getSession().getId();
        assertThatThrownBy(() -> login(request, "incorrect")).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.getSession().getId()).isEqualTo(before);
        assertThat(controller.session(request).authenticated()).isFalse();
    }

    private void login(MockHttpServletRequest request, String password) {
        controller.login(new AdminLoginRequest("admin", password, "challenge", 10), request);
    }

    private AdminAuthService service() {
        AdminAuthService service = new AdminAuthService(directory.resolve("admin.properties").toString(), "admin", "admin123");
        service.load();
        return service;
    }
}
