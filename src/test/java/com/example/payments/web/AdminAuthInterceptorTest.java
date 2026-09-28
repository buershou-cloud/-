package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminAuthInterceptorTest {
    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(mock(AdminAuthService.class));

    @Test
    void allowsSignedDouyinCallbackToReachItsVerificationController() throws Exception {
        assertThat(allowed("POST", "/api/v1/douyin/notify/dy-main")).isTrue();
        assertThat(allowed("GET", "/api/v1/douyin/notify/dy-main")).isFalse();
        assertThat(allowed("POST", "/api/v1/douyin/notify/dy-main/private")).isFalse();
    }

    @Test
    void onlyExplicitPublicIntegrationFilesAreReadableWithoutLogin() throws Exception {
        for (String path : new String[]{"/merchant-integration.js", "/sdk/merchant-client.mjs", "/sdk/merchant-example.mjs", "/docs/merchant-api.md"}) {
            assertThat(allowed("GET", path)).as(path).isTrue();
            assertThat(allowed("POST", path)).as(path).isFalse();
        }
        assertThat(allowed("GET", "/sdk/private.properties")).isFalse();
        assertThat(allowed("GET", "/docs/private.md")).isFalse();
        assertThat(allowed("GET", "/api/v1/merchants")).isFalse();
    }

    private boolean allowed(String method, String path) throws Exception {
        return interceptor.preHandle(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new Object());
    }
}
