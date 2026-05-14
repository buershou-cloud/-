package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.OnboardingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlipayPaymentProviderOnboardingTest {

    @Test
    void directStandardOnboardingMapsLocalBusinessNoToExternalId() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("out_biz_no", "OLD-001");
        payload.put("alias_name", "演示门店");

        provider.onboard(
                channel(),
                new OnboardingRequest("ZFT-001", null, "app-auth-token", List.of("ali-direct"), payload)
        );

        assertThat(client.method).isEqualTo("ant.merchant.expand.indirect.zft.simplecreate");
        assertThat(client.bizContent)
                .containsEntry("external_id", "ZFT-001")
                .containsEntry("alias_name", "演示门店")
                .doesNotContainKey("out_biz_no");
    }

    @Test
    void legacyOnboardingMethodKeepsOutBizNo() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.getOperations().setOnboardingMethod("alipay.open.agent.create");
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(properties, client);

        provider.onboard(
                channel(),
                new OnboardingRequest("LEGACY-001", null, null, List.of("ali-direct"), Map.of("account", "demo@example.com"))
        );

        assertThat(client.method).isEqualTo("alipay.open.agent.create");
        assertThat(client.bizContent).containsEntry("out_biz_no", "LEGACY-001");
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-direct");
        channel.setProvider("ALIPAY_DIRECT");
        return channel;
    }

    private static class CapturingAlipayClient extends AlipayOpenApiClient {
        private String method;
        private Map<String, Object> bizContent;

        private CapturingAlipayClient() {
            super(new ObjectMapper());
        }

        @Override
        public AlipayGatewayResponse execute(
                PaymentGatewayProperties.Channel channel,
                String method,
                Map<String, Object> bizContent,
                AlipayRequestOptions options
        ) {
            this.method = method;
            this.bizContent = new LinkedHashMap<>(bizContent);
            return new AlipayGatewayResponse(
                    method,
                    "mock_response",
                    true,
                    "10000",
                    "Success",
                    null,
                    null,
                    Map.of("code", "10000", "msg", "Success"),
                    Map.of()
            );
        }
    }
}
