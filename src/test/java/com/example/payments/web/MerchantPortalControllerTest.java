package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.merchant.DemoMerchantCreateRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.DemoMerchantUpdateRequest;
import com.example.payments.merchant.MerchantLoginRequest;
import com.example.payments.merchant.MerchantSession;
import com.example.payments.order.DemoOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantPortalControllerTest {
    private final DemoMerchantService merchants = new DemoMerchantService();
    private final DemoMerchantView merchant = merchants.create(new DemoMerchantCreateRequest("M1", "Merchant", null, null, null, null, null));
    private final AdminAuthService admins = mock(AdminAuthService.class);
    private final MerchantPortalController controller = new MerchantPortalController(merchants, mock(DemoOrderService.class), admins);

    @Test
    void publicDemoFlagCannotBypassMerchantCredentials() {
        MockHttpServletRequest http = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.login(new MerchantLoginRequest("M1", "wrong", true), http))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(http.getSession(false)).isNull();
    }

    @Test
    void validMerchantCredentialsStillLogInWithoutAdminSession() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        String oldSessionId = http.getSession().getId();

        assertThat(controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http).merchant().merchantId())
                .isEqualTo("M1");
        assertThat(http.getSession().getId()).isNotEqualTo(oldSessionId);
        assertThat(MerchantSession.isMerchant(http, "M1", merchants)).isTrue();
        assertThat(http.getSession().getAttribute(MerchantSession.CREDENTIAL_ATTRIBUTE)).isNotEqualTo(merchant.md5Key());
    }

    @Test
    void existingAdminCanUseShortcutButDeletedAdminCannot() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        AdminSession.login(http, "admin", "current-admin-credential");
        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(true);
        assertThat(controller.login(new MerchantLoginRequest("M1", null, true), http).merchant().merchantId()).isEqualTo("M1");
        assertThat(http.getSession().getAttribute(MerchantSession.MERCHANT_ID_ATTRIBUTE)).isNull();

        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(false);
        assertThatThrownBy(() -> controller.login(new MerchantLoginRequest("M1", null, true), http))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void administratorShortcutDoesNotLeaveMerchantPrivilegesAfterAdministratorSessionIsRevoked() throws Exception {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/v1/merchants/M1/reset-md5");
        controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http);
        AdminSession.login(http, "admin", "current-admin-credential");
        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(true);
        controller.login(new MerchantLoginRequest("M1", null, true), http);
        assertThat(http.getSession().getAttribute(MerchantSession.MERCHANT_ID_ATTRIBUTE)).isNull();
        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(false);
        assertThat(new AdminAuthInterceptor(admins, merchants).preHandle(http, new MockHttpServletResponse(), new Object())).isFalse();
    }

    @Test
    void disabledMerchantCannotLoginEvenWithAnAdministratorShortcut() {
        merchants.update("M1", new DemoMerchantUpdateRequest(null, null, "禁用", null, null, null));
        MockHttpServletRequest http = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("禁用");
        AdminSession.login(http, "admin", "current-admin-credential");
        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(true);
        assertThatThrownBy(() -> controller.login(new MerchantLoginRequest("M1", null, true), http))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("禁用");
        assertThat(http.getSession().getAttribute(MerchantSession.MERCHANT_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void credentialRotationRevokesOldMerchantSessionsForEveryCredentialEndpoint() throws Exception {
        for (String rotation : new String[]{"md5", "rsa", "mode"}) {
            DemoMerchantView current = merchants.detail("M1");
            MockHttpServletRequest old = new MockHttpServletRequest();
            controller.login(new MerchantLoginRequest("M1", current.md5Key(), false), old);
            if (rotation.equals("md5")) merchants.resetMd5("M1");
            if (rotation.equals("rsa")) merchants.generateRsa2("M1");
            if (rotation.equals("mode")) merchants.updateSignMode("M1", "RSA2");
            for (String endpoint : new String[]{"reset-md5", "generate-rsa2", "sign-mode"}) {
                old.setMethod("POST");
                old.setRequestURI("/api/v1/merchants/M1/" + endpoint);
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertThat(new AdminAuthInterceptor(admins, merchants).preHandle(old, response, new Object())).isFalse();
                assertThat(response.getStatus()).isEqualTo(401);
            }
            assertThat(old.getSession().getAttribute(MerchantSession.CREDENTIAL_ATTRIBUTE)).isNull();
        }
    }

    @Test
    void disablingMerchantBlocksExistingPortalSessionButAdministratorCanStillManageIt() throws Exception {
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/v1/merchants/M1/reset-md5");
        controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http);
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(admins, merchants);
        assertThat(interceptor.preHandle(http, new MockHttpServletResponse(), new Object())).isTrue();
        merchants.update("M1", new DemoMerchantUpdateRequest(null, null, "禁用", null, null, null));
        assertThat(interceptor.preHandle(http, new MockHttpServletResponse(), new Object())).isFalse();
        AdminSession.login(http, "admin", "current-admin-credential");
        when(admins.matchesSession("admin", "current-admin-credential")).thenReturn(true);
        assertThat(interceptor.preHandle(http, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void merchantIdWithoutCredentialBindingAndOtherMerchantsSessionAreRejected() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.getSession().setAttribute(MerchantSession.MERCHANT_ID_ATTRIBUTE, "M1");
        assertThat(MerchantSession.isMerchant(http, "M1", merchants)).isFalse();
        controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http);
        assertThat(MerchantSession.isMerchant(http, "M2", merchants)).isFalse();
        assertThat(MerchantSession.isMerchant(http, "M1", merchants)).isTrue();
    }
}
