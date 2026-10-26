package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.merchant.DemoMerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminAuthInterceptorTest {
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(mock(AdminAuthService.class), mock(DemoMerchantService.class));

    @Test
    void allowsSignedDouyinCallbackToReachItsVerificationController() throws Exception {
        assertThat(allowed("POST", "/api/v1/douyin/notify/dy-main")).isTrue();
        assertThat(allowed("GET", "/api/v1/douyin/notify/dy-main")).isFalse();
        assertThat(allowed("POST", "/api/v1/douyin/notify/dy-main/private")).isFalse();
    }

    @Test
    void onlyExplicitPublicIntegrationFilesAreReadableWithoutLogin() throws Exception {
        for (String path : new String[]{"/ui-theme.css", "/merchant-integration.js", "/sdk/merchant-client.mjs", "/sdk/merchant-example.mjs", "/docs/merchant-api.md"}) {
            assertThat(allowed("GET", path)).as(path).isTrue();
            assertThat(allowed("POST", path)).as(path).isFalse();
        }
        assertThat(allowed("GET", "/sdk/private.properties")).isFalse();
        assertThat(allowed("GET", "/docs/private.md")).isFalse();
        assertThat(allowed("GET", "/api/v1/merchants")).isFalse();
    }

    @Test
    void anonymousThemeGetSupportsContextPathAndCacheVersion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/gateway/ui-theme.css");
        request.setContextPath("/gateway");
        request.setQueryString("ui=20261025");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void themeExceptionDoesNotExposeOtherFilesOrProtectedApis() throws Exception {
        for (String path : new String[]{"/private.css", "/ui-theme.css/private", "/ui-theme.css.bak"}) {
            assertThat(allowed("GET", path)).as(path).isFalse();
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/v1/orders/recent"),
                response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHENTICATED");
    }

    private boolean allowed(String method, String path) throws Exception {
        return interceptor.preHandle(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new Object());
    }
}
