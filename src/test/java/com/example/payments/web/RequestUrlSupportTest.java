package com.example.payments.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class RequestUrlSupportTest {
    @Test void includesApplicationContextBehindProxy() {
        var request = new MockHttpServletRequest();
        request.setContextPath("/gateway");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "pay.example.com");
        assertThat(RequestUrlSupport.apiBase(request)).isEqualTo("https://pay.example.com/gateway/api/v1");
        assertThat(RequestUrlSupport.alipayNotifyUrl(request, "ali-main"))
                .isEqualTo("https://pay.example.com/gateway/api/v1/alipay/notify/ali-main");
    }
}
