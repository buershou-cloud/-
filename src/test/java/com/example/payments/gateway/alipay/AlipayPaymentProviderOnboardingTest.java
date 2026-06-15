package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    void directZftOrderQueryMapsBusinessNoToExternalId() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.onboard(
                channel(),
                new OnboardingRequest(
                        "ZFT-QUERY-001",
                        "ant.merchant.expand.indirect.zftorder.query",
                        null,
                        List.of("ali-direct"),
                        Map.of("out_biz_no", "WRONG-LEGACY")
                )
        );

        assertThat(client.method).isEqualTo("ant.merchant.expand.indirect.zftorder.query");
        assertThat(client.bizContent)
                .containsEntry("external_id", "ZFT-QUERY-001")
                .doesNotContainKey("out_biz_no");
    }

    @Test
    void directZftDeleteMapsBusinessNoToExternalId() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.onboard(
                channel(),
                new OnboardingRequest(
                        "ZFT-DELETE-001",
                        "ant.merchant.expand.indirect.zft.delete",
                        null,
                        List.of("ali-direct"),
                        Map.of()
                )
        );

        assertThat(client.method).isEqualTo("ant.merchant.expand.indirect.zft.delete");
        assertThat(client.bizContent).containsEntry("external_id", "ZFT-DELETE-001");
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
    void pageProductKeepsAlipayWalletIntegrationType() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PAGE,
                        "PAGE-MOBILE-001",
                        "page pay mobile",
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
                        Map.of(
                                "integration_type", "ALIAPP",
                                "request_from_url", "https://example.com/cashier.html"
                        ),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.page.pay");
        assertThat(client.bizContent).containsEntry("product_code", "FAST_INSTANT_TRADE_PAY");
        assertThat(client.bizContent).containsEntry("integration_type", "ALIAPP");
        assertThat(client.bizContent).containsEntry("request_from_url", "https://example.com/cashier.html");
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
    void paymentCodeProductUsesFaceToFaceTradePayWithAuthCode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PAYMENT_CODE,
                        "PAYMENT-CODE-001",
                        "payment code",
                        new BigDecimal("1.00"),
                        "281234567890123456",
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

        assertThat(client.method).isEqualTo("alipay.trade.pay");
        assertThat(client.bizContent)
                .containsEntry("out_trade_no", "PAYMENT-CODE-001")
                .containsEntry("scene", "bar_code")
                .containsEntry("auth_code", "281234567890123456")
                .containsEntry("product_code", "FACE_TO_FACE_PAYMENT");
        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.qrCode()).isNull();
        assertThat(response.redirectHtml()).isNull();
    }

    @Test
    void paymentCodeProductRejectsMissingAuthCode() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        assertThatThrownBy(() -> provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PAYMENT_CODE,
                        "PAYMENT-CODE-002",
                        "payment code",
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
                .hasMessageContaining("requires authCode");
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
                .containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", "2021000000000000");
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
                .hasMessageContaining("requires buyerId, buyerOpenId, or OAuth authCode");
    }

    @Test
    void jsapiProductCanExchangeOauthAuthCodeForBuyerIdentifier() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_JSAPI,
                        "JSAPI-003",
                        "jsapi pay",
                        new BigDecimal("1.00"),
                        "oauth-auth-code",
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

        assertThat(client.methods)
                .containsExactly("alipay.system.oauth.token", "alipay.trade.create");
        assertThat(client.businessParams)
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "oauth-auth-code");
        assertThat(client.bizContent)
                .containsEntry("buyer_id", "2088102146225135")
                .containsEntry("product_code", "JSAPI_PAY")
                .containsEntry("op_app_id", "2021000000000000")
                .doesNotContainKey("auth_code");
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
    void h5PreauthProductCreatesFreezeOrderString() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.pay(
                standardChannel(),
                new PayCreateRequest(
                        PaymentProduct.ALIPAY_PREAUTH_H5,
                        "PREAUTH-H5-001",
                        "h5 preauth",
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
                        Map.of(),
                        null,
                        null
                )
        );

        assertThat(client.method).isEqualTo("alipay.fund.auth.order.app.freeze");
        assertThat(client.bizContent)
                .containsEntry("out_order_no", "PREAUTH-H5-001")
                .containsEntry("out_request_no", "PREAUTH-H5-001_h5")
                .containsEntry("product_code", "PREAUTH_PAY")
                .containsEntry("timeout_express", "30m")
                .doesNotContainKeys("pay_timeout", "preauth_method");
        assertThat(response.raw()).containsEntry("request_method", "alipay.fund.auth.order.app.freeze");
        assertThat(response.raw()).containsKey("order_string");
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
    void preauthCaptureRejectsBlankAuthNoBeforeCallingAlipay() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        assertThatThrownBy(() -> provider.preauthCapture(
                standardChannel(),
                new PreauthCaptureRequest(
                        "PREAUTH-001",
                        "PREAUTH-001-PAY",
                        "",
                        "preauth capture",
                        new BigDecimal("1.00"),
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of()
                )
        ))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("预授权转支付缺少支付宝授权号");
        assertThat(client.method).isNull();
    }

    @Test
    void preauthCaptureKeepsAuthNoWhenExtraContainsBlankAuthNo() {
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
                        null,
                        null,
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of(
                                "auth_no", "",
                                "product_code", "",
                                "preauth_product_code", "PREAUTH_PAY",
                                "out_trade_no", ""
                        )
                )
        );

        assertThat(client.method).isEqualTo("alipay.trade.pay");
        assertThat(client.bizContent)
                .containsEntry("auth_no", "2026052900000000000000000001")
                .containsEntry("out_trade_no", "PREAUTH-001-PAY")
                .containsEntry("product_code", "PREAUTH_PAY")
                .doesNotContainKey("preauth_product_code");
    }

    @Test
    void preauthQueryUsesOfficialFundAuthOperationDetailQuery() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.preauthQuery(
                standardChannel(),
                new PaymentQueryRequest(
                        "PREAUTH-H5-001",
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of(
                                "out_request_no", "PREAUTH-H5-001_h5",
                                "operation_type", "FREEZE"
                        )
                )
        );

        assertThat(client.method).isEqualTo("alipay.fund.auth.operation.detail.query");
        assertThat(client.bizContent)
                .containsEntry("out_order_no", "PREAUTH-H5-001")
                .containsEntry("out_request_no", "PREAUTH-H5-001_h5")
                .containsEntry("operation_type", "FREEZE")
                .doesNotContainKeys("out_trade_no", "trade_no");
        assertThat(response.tradeNo()).isEqualTo("2026052900000000000000000001");
    }

    @Test
    void preauthQueryReadsNestedAuthNoFromOperationDetail() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        client.operationDetailAuthNoNested = true;
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.preauthQuery(
                standardChannel(),
                new PaymentQueryRequest(
                        "PREAUTH-H5-001",
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of("out_request_no", "PREAUTH-H5-001_h5")
                )
        );

        assertThat(response.tradeNo()).isEqualTo("2026052900000000000000000001");
    }

    @Test
    void preauthQueryPrefersAuthNoOverTradeNoWhenBothExist() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        client.operationDetailIncludesTradeNo = true;
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        GatewayResponse response = provider.preauthQuery(
                standardChannel(),
                new PaymentQueryRequest(
                        "PREAUTH-H5-001",
                        null,
                        null,
                        List.of("ali-main"),
                        Map.of("out_request_no", "PREAUTH-H5-001_h5")
                )
        );

        assertThat(response.tradeNo()).isEqualTo("2026052900000000000000000001");
    }

    @Test
    void preauthUnfreezeUsesOfficialUnfreezeFields() {
        CapturingAlipayClient client = new CapturingAlipayClient();
        AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);

        provider.preauthUnfreeze(
                standardChannel(),
                new PreauthUnfreezeRequest(
                        "PREAUTH-001",
                        "2026052900000000000000000001",
                        "PREAUTH-001-UF-1",
                        new BigDecimal("1.00"),
                        "partial unfreeze",
                        null,
                        List.of("ali-main"),
                        Map.of("auth_no", "")
                )
        );

        assertThat(client.method).isEqualTo("alipay.fund.auth.order.unfreeze");
        assertThat(client.bizContent)
                .containsEntry("auth_no", "2026052900000000000000000001")
                .containsEntry("out_request_no", "PREAUTH-001-UF-1")
                .containsEntry("amount", "1.00")
                .containsEntry("remark", "partial unfreeze")
                .doesNotContainKey("out_trade_no");
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
        channel.getAlipay().setAppId("2021000000000000");
        return channel;
    }

    private static class CapturingAlipayClient extends AlipayOpenApiClient {
        private String method;
        private Map<String, Object> bizContent;
        private Map<String, String> businessParams;
        private final List<String> methods = new ArrayList<>();
        private boolean operationDetailAuthNoNested;
        private boolean operationDetailIncludesTradeNo;

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
            this.methods.add(method);
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
            this.methods.add(method);
            this.method = method;
            this.bizContent = new LinkedHashMap<>(bizContent);
            return "app_id=2021000000000000&method=" + method + "&biz_content=mock&sign=mock";
        }

        @Override
        public AlipayGatewayResponse executeWithParams(
                PaymentGatewayProperties.Channel channel,
                String method,
                Map<String, String> businessParams,
                AlipayRequestOptions options
        ) {
            this.methods.add(method);
            this.method = method;
            this.businessParams = new LinkedHashMap<>(businessParams);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", "10000");
            response.put("msg", "Success");
            response.put("user_id", "2088102146225135");
            response.put("open_id", "074a00d0000000000000000000000000");
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

        @Override
        public AlipayGatewayResponse execute(
                PaymentGatewayProperties.Channel channel,
                String method,
                Map<String, Object> bizContent,
                AlipayRequestOptions options
        ) {
            this.methods.add(method);
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
            if ("alipay.fund.auth.operation.detail.query".equals(method)) {
                response.put("out_order_no", asString(bizContent.get("out_order_no")));
                if (operationDetailIncludesTradeNo) {
                    response.put("trade_no", "2026061500000000000000009999");
                }
                if (operationDetailAuthNoNested) {
                    response.put("operation_detail", Map.of("auth_no", "2026052900000000000000000001"));
                } else {
                    response.put("auth_no", "2026052900000000000000000001");
                }
                response.put("status", "SUCCESS");
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
