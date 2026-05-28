package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    @Test
    void desktopCashierKeepsSelectedRedirectProduct() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_WAP,
                        "CASHIER-001",
                        "cashier",
                        new BigDecimal("1.00"),
                        null,
                        null,
                        null,
                        null,
                        "10m",
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-direct"),
                        Map.of("cashier", true, "cashierDesktopQr", true, "merchantName", "demo"),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.wap.pay");
        assertThat(client.bizContent)
                .containsEntry("product_code", "QUICK_WAP_WAY")
                .doesNotContainKeys("cashier", "cashierDesktopQr", "merchantName");
        assertThat(response.qrCode()).isNull();
        assertThat(response.redirectUrl()).isNull();
        assertThat(response.redirectHtml()).contains("alipay.trade.wap.pay");
    }

    @Test
    void faceToFaceProductCreatesQrWithoutBuyerAuthCode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_F2F,
                        "F2F-001",
                        "face to face qr",
                        new BigDecimal("1.00"),
                        null,
                        null,
                        null,
                        null,
                        "10m",
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of(),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.precreate");
        assertThat(client.bizContent)
                .containsEntry("product_code", "FACE_TO_FACE_PAYMENT")
                .doesNotContainKeys("auth_code", "scene");
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.test/F2F-001");
    }

    @Test
    void orderCodeProductUsesPagePayOrderCodeMode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_ORDER_CODE,
                        "ORDER-CODE-001",
                        "order code",
                        new BigDecimal("1.00"),
                        null,
                        null,
                        null,
                        null,
                        "10m",
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of(),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.page.pay");
        assertThat(client.bizContent)
                .containsEntry("product_code", "FAST_INSTANT_TRADE_PAY")
                .containsEntry("qr_pay_mode", "4")
                .containsEntry("qrcode_width", "180")
                .doesNotContainEntry("product_code", "FACE_TO_FACE_PAYMENT");
        assertThat(response.redirectHtml()).contains("alipay.trade.page.pay");
        assertThat(response.qrCode()).isNull();
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-direct");
        channel.setProvider("ALIPAY_DIRECT");
        return channel;
    }

    private static PaymentGatewayProperties.Channel standardChannel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-main");
        channel.setProvider("ALIPAY");
        return channel;
    }

    private static class CapturingAlipayClient extends AlipayOpenApiClient {
        private String method;
        private Map<String, Object> bizContent;

        private CapturingAlipayClient() {
            super(new ObjectMapper());
        }

        @Override
        public String pageForm(
                PaymentGatewayProperties.Channel channel,
                String method,
                Map<String, Object> bizContent,
                AlipayRequestOptions options
        ) {
            this.method = method;
            this.bizContent = new LinkedHashMap<>(bizContent);
            return "<form action=\"https://openapi.alipay.com/gateway.do\" method=\"post\">"
                    + "<input name=\"method\" value=\"" + method + "\">"
                    + "</form>";
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
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", "10000");
            response.put("msg", "Success");
            if ("alipay.trade.precreate".equals(method)) {
                response.put("out_trade_no", asString(bizContent.get("out_trade_no")));
                response.put("qr_code", "https://qr.alipay.test/" + bizContent.get("out_trade_no"));
            }
            return new AlipayGatewayResponse(
                    method,
                    "mock_response",
                    true,
                    "10000",
                    "Success",
                    null,
                    null,
                    response,
                    Map.of()
            );
        }

        private static String asString(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }
}
