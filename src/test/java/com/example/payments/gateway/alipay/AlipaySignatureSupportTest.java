package com.example.payments.gateway.alipay;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlipaySignatureSupportTest {

    @Test
    void requestSigningIncludesSignTypeButExcludesSign() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("method", "alipay.trade.precreate");
        params.put("app_id", "2021000000000000");
        params.put("sign_type", "RSA2");
        params.put("sign", "ignored");
        params.put("biz_content", "{\"total_amount\":\"0.01\"}");

        assertThat(AlipaySignatureSupport.canonicalizeForRequestSign(params))
                .isEqualTo("app_id=2021000000000000&biz_content={\"total_amount\":\"0.01\"}&method=alipay.trade.precreate&sign_type=RSA2");
    }

    @Test
    void responseVerificationExcludesSignAndSignType() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("method", "alipay.trade.precreate");
        params.put("app_id", "2021000000000000");
        params.put("sign_type", "RSA2");
        params.put("sign", "ignored");
        params.put("biz_content", "{\"total_amount\":\"0.01\"}");

        assertThat(AlipaySignatureSupport.canonicalizeForVerify(params))
                .isEqualTo("app_id=2021000000000000&biz_content={\"total_amount\":\"0.01\"}&method=alipay.trade.precreate");
    }
}
