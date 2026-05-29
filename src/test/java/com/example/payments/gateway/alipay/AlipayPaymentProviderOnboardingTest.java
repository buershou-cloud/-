package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void pageProductUsesOfficialDesktopCashierProductCode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PAGE,
                        "PAGE-001",
                        "page pay",
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
        assertThat(client.bizContent).containsEntry("product_code", "FAST_INSTANT_TRADE_PAY");
        assertThat(response.redirectHtml()).contains("alipay.trade.page.pay");
    }

    @Test
    void appProductUsesOfficialAppPayOrderString() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_APP,
                        "APP-001",
                        "app pay",
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

        assertThat(client.method).isEqualTo("alipay.trade.app.pay");
        assertThat(client.bizContent)
                .containsEntry("out_trade_no", "APP-001")
                .containsEntry("product_code", "QUICK_MSECURITY_PAY")
                .containsEntry("timeout_express", "10m");
        assertThat(response.redirectUrl()).contains("method=alipay.trade.app.pay");
        assertThat(response.raw())
                .containsEntry("request_method", "alipay.trade.app.pay")
                .containsEntry("request_product_code", "QUICK_MSECURITY_PAY");
        assertThat(response.raw().get("order_string")).isEqualTo(response.redirectUrl());
        assertThat(response.qrCode()).isNull();
        assertThat(response.redirectHtml()).isNull();
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
    void orderCodeProductUsesPrecreateOfflineQrCode() {
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

        assertThat(client.method).isEqualTo("alipay.trade.precreate");
        assertThat(client.bizContent)
                .containsEntry("product_code", "QR_CODE_OFFLINE")
                .doesNotContainEntry("product_code", "FACE_TO_FACE_PAYMENT")
                .doesNotContainKeys("qr_pay_mode", "qrcode_width");
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.test/ORDER-CODE-001");
        assertThat(response.redirectHtml()).isNull();
    }

    @Test
    void cancelUsesOfficialTradeCancelApi() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.cancel(
                standardChannel(),
                new PaymentCancelRequest(
                        "ORDER-CODE-001",
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of()
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.cancel");
        assertThat(client.bizContent).containsEntry("out_trade_no", "ORDER-CODE-001");
        assertThat(response.status()).isEqualTo(PaymentStatus.CLOSED);
    }

    @Test
    void jsapiProductUsesTradeCreateAndPinsOfficialProductCode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_JSAPI,
                        "JSAPI-001",
                        "jsapi pay",
                        new BigDecimal("1.00"),
                        null,
                        "2088102146225135",
                        null,
                        null,
                        "10m",
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of("product_code", "WRONG_PRODUCT"),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.create");
        assertThat(client.bizContent)
                .containsEntry("buyer_id", "2088102146225135")
                .containsEntry("product_code", "JSAPI_PAY");
    }

    @Test
    void jsapiProductRejectsMissingBuyerIdentifier() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        assertThatThrownBy(() -> provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_JSAPI,
                        "JSAPI-002",
                        "jsapi pay",
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
        ))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("requires buyerId or buyerOpenId");
    }

    @Test
    void preauthProductUsesVoucherCreateQrByDefault() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PREAUTH,
                        "PREAUTH-001",
                        "preauth",
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

        assertThat(client.method).isEqualTo("alipay.fund.auth.order.voucher.create");
        assertThat(client.bizContent)
                .containsEntry("out_order_no", "PREAUTH-001")
                .containsEntry("out_request_no", "PREAUTH-001_voucher")
                .containsEntry("product_code", "PRE_AUTH")
                .containsEntry("timeout_express", "10m")
                .doesNotContainKey("pay_timeout");
        assertThat(response.qrCode()).isEqualTo("https://qr.alipay.test/PREAUTH-001");
    }

    @Test
    void preauthProductCanOverrideOfficialMethodAndProduct() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PREAUTH,
                        "PREAUTH-002",
                        "preauth",
                        new BigDecimal("1.00"),
                        null,
                        null,
                        null,
                        null,
                        "30m",
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of(
                                "preauth_method", "alipay.fund.auth.order.app.freeze",
                                "product_code", "PRE_AUTH_ONLINE"
                        ),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.fund.auth.order.app.freeze");
        assertThat(client.bizContent)
                .containsEntry("product_code", "PRE_AUTH_ONLINE")
                .containsEntry("timeout_express", "30m")
                .doesNotContainKeys("pay_timeout", "preauth_method");
    }

    @Test
    void preauthCaptureUsesOfficialTradePayFields() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.preauthCapture(
                standardChannel(),
                new PreauthCaptureRequest(
                        "PREAUTH-001",
                        "PREAUTH-001-PAY",
                        "2026052900000000000000000001",
                        "preauth capture",
                        new BigDecimal("1.00"),
                        "2088102146225135",
                        "2088102146225136",
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of()
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.pay");
        assertThat(client.bizContent)
                .containsEntry("out_trade_no", "PREAUTH-001-PAY")
                .containsEntry("product_code", "PRE_AUTH")
                .containsEntry("auth_no", "2026052900000000000000000001")
                .containsEntry("scene", "bar_code")
                .containsEntry("subject", "preauth capture")
                .containsEntry("total_amount", "1.00")
                .containsEntry("buyer_id", "2088102146225135")
                .containsEntry("seller_id", "2088102146225136")
                .containsEntry("auth_confirm_mode", "COMPLETE")
                .doesNotContainKey("auth_code");
    }

    @Test
    void complaintListUsesOfficialSecurityRiskBatchQueryFields() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.queryComplaints(
                standardChannel(),
                new ComplaintQueryRequest(
                        null,
                        "2026-05-01 00:00:00",
                        "2026-05-29 23:59:59",
                        2,
                        20,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of()
                )
        );

        assertThat(client.method).isEqualTo("alipay.security.risk.complaint.info.batchquery");
        assertThat(client.bizContent)
                .containsEntry("gmt_complaint_start", "2026-05-01 00:00:00")
                .containsEntry("gmt_complaint_end", "2026-05-29 23:59:59")
                .containsEntry("current_page_num", 2)
                .containsEntry("page_size", 20)
                .doesNotContainKeys("begin_time", "end_time", "page_num");
    }

    @Test
    void complaintDetailUsesOfficialSecurityRiskQueryComplainId() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.queryComplaints(
                standardChannel(),
                new ComplaintQueryRequest(
                        "1000000001",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of("record_id", 1000000001L)
                )
        );

        assertThat(client.method).isEqualTo("alipay.security.risk.complaint.info.query");
        assertThat(client.bizContent)
                .containsEntry("complain_id", "1000000001")
                .containsEntry("record_id", 1000000001L)
                .doesNotContainKey("complaint_id");
    }

    @Test
    void explicitComplaintBatchQueryCanFilterByTaskId() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.queryComplaints(
                standardChannel(),
                new ComplaintQueryRequest(
                        "TSK20260529001",
                        null,
                        null,
                        1,
                        10,
                        "alipay.security.risk.complaint.info.batchquery",
                        null,
                        List.of("ali-main"),
                        Map.of("status_list", List.of("WAIT_PROCESS"))
                )
        );

        assertThat(client.method).isEqualTo("alipay.security.risk.complaint.info.batchquery");
        assertThat(client.bizContent)
                .containsEntry("task_id", "TSK20260529001")
                .containsEntry("current_page_num", 1)
                .containsEntry("page_size", 10)
                .containsEntry("status_list", List.of("WAIT_PROCESS"));
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
        public String orderString(
                PaymentGatewayProperties.Channel channel,
                String method,
                Map<String, Object> bizContent,
                AlipayRequestOptions options
        ) {
            this.method = method;
            this.bizContent = new LinkedHashMap<>(bizContent);
            return "app_id=2021000000000000&method=" + method + "&biz_content=mock&sign=mock";
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
            if ("alipay.fund.auth.order.voucher.create".equals(method)) {
                response.put("out_order_no", asString(bizContent.get("out_order_no")));
                response.put("code_value", "https://qr.alipay.test/" + bizContent.get("out_order_no"));
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
