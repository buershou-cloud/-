package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.merchant.DemoMerchantCreateRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.MerchantLoginRequest;
import com.example.payments.merchant.MerchantSession;
import com.example.payments.order.DemoOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

        assertThat(controller.login(new MerchantLoginRequest("M1", merchant.md5Key(), false), http).merchant().merchantId())
                .isEqualTo("M1");
        assertThat(MerchantSession.isMerchant(http, "M1")).isTrue();
    }

    @Test
    void existingAdminCanUseShortcutButDeletedAdminCannot() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.getSession().setAttribute(AdminSession.USERNAME_ATTRIBUTE, "admin");
        when(admins.hasUsername("admin")).thenReturn(true);
        assertThat(controller.login(new MerchantLoginRequest("M1", null, true), http).merchant().merchantId()).isEqualTo("M1");

        when(admins.hasUsername("admin")).thenReturn(false);
        assertThatThrownBy(() -> controller.login(new MerchantLoginRequest("M1", null, true), http))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
