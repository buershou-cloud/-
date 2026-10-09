package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AlipayPaymentProviderPageQrTest {

    private static final String PAGE_METHOD = "alipay.trade.page.pay";
    private static final String SIGNED_URL = "https://openapi.alipay.com/gateway.do?sign=sensitive-signature";
    private static final String OFFICIAL_QR = "https://qr.alipay.com/upx-test-original-order";
    private final AlipayOpenApiClient client = mock(AlipayOpenApiClient.class);
    private final AlipayPageQrClient pageQrClient = mock(AlipayPageQrClient.class);
    private final AlipayPaymentProvider provider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client, pageQrClient);

    @Test
    void mobileCashierObtainsOriginalPageOrderQrWithoutChangingFinancialParametersOrInput() {
        PaymentGatewayProperties.Channel channel = channel(false);
        Map<String, Object> extra = new LinkedHashMap<>(Map.of(
                "cashier", true, "cashierMobilePageQr", true,
                "integration_type", "ALIAPP", "qr_pay_mode", "1", "product_code", "WRONG",
                "request_from_url", "https://merchant.example/cashier.html",
                "merchantId", "M1", "merchantName", "Merchant"));
        Map<String, Object> originalExtra = new LinkedHashMap<>(extra);
        Map<String, Object> settle = Map.of("profit_sharing", true);
        Map<String, Object> royalty = Map.of("royalty_type", "ROYALTY");
        PayCreateRequest request = request(PaymentProduct.ALIPAY_PAGE, extra, settle, royalty);
        when(client.pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any())).thenReturn(SIGNED_URL);
        when(pageQrClient.resolve(SIGNED_URL)).thenReturn(OFFICIAL_QR);

        GatewayResponse response = provider.pay(channel, request);

        ArgumentCaptor<Map<String, Object>> biz = mapCaptor();
        ArgumentCaptor<AlipayRequestOptions> options = ArgumentCaptor.forClass(AlipayRequestOptions.class);
        verify(client).pageUrl(same(channel), eq(PAGE_METHOD), biz.capture(), options.capture());
        verify(pageQrClient).resolve(SIGNED_URL);
        verifyNoMoreInteractions(client, pageQrClient);
        assertThat(biz.getValue()).hasSize(11).containsAllEntriesOf(Map.of(
                "out_trade_no", "CASHIER-ORIGINAL-001", "subject", "Original subject", "total_amount", "12.34",
                "quit_url", "https://merchant.example/quit", "timeout_express", "10m",
                "settle_info", settle, "royalty_info", royalty,
                "product_code", "FAST_INSTANT_TRADE_PAY", "qr_pay_mode", "4",
                "integration_type", "PCWEB"));
        assertThat(biz.getValue().get("request_from_url")).isEqualTo("https://merchant.example/cashier.html");
        assertThat(options.getValue()).isEqualTo(new AlipayRequestOptions("original-app-auth",
                "https://merchant.example/notify", "https://merchant.example/return"));
        assertThat(extra).isEqualTo(originalExtra);
        assertThat(request.settleInfo()).isSameAs(settle);
        assertThat(request.royaltyInfo()).isSameAs(royalty);
        assertThat(response.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(response.code()).isEqualTo("PAGE_QR_CREATED");
        assertOriginalIdentityAndSafeMetadata(response);
        assertThat(response.qrCode()).isEqualTo(OFFICIAL_QR);
        assertThat(response.redirectHtml()).isNull();
        assertThat(response.redirectUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAGE_QR_TIMEOUT", "PAGE_QR_PARSE_ERROR", "PAGE_QR_REDIRECT_REJECTED"})
    void pageRetrievalFailuresRemainUnconfirmedWithOriginalIdentityAndNoFallback(String failureCode) {
        PaymentGatewayProperties.Channel channel = channel(false);
        when(client.pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any())).thenReturn(SIGNED_URL);
        when(pageQrClient.resolve(SIGNED_URL)).thenThrow(new GatewayException(failureCode,
                "Unsafe upstream diagnostic " + SIGNED_URL));

        GatewayResponse response = provider.pay(channel, request(PaymentProduct.ALIPAY_PAGE, mobileExtra(), null, null));

        assertPendingWithoutFallback(response);
        verify(client).pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any());
        verify(pageQrClient).resolve(SIGNED_URL);
        verifyNoMoreInteractions(client, pageQrClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingResolvedQrCannotBeReportedAsCreated(String qrCode) {
        PaymentGatewayProperties.Channel channel = channel(false);
        when(client.pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any())).thenReturn(SIGNED_URL);
        when(pageQrClient.resolve(SIGNED_URL)).thenReturn(qrCode);

        assertPendingWithoutFallback(provider.pay(channel,
                request(PaymentProduct.ALIPAY_PAGE, mobileExtra(), null, null)));

        verify(client).pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any());
        verify(pageQrClient).resolve(SIGNED_URL);
        verifyNoMoreInteractions(client, pageQrClient);
    }

    @Test
    void signingFailureAlsoRetainsOriginalIdentityWithoutExposingCredentials() {
        PaymentGatewayProperties.Channel channel = channel(false);
        when(client.pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any()))
                .thenThrow(new GatewayException("ALIPAY_SIGN_ERROR", "sensitive-signature"));

        assertPendingWithoutFallback(provider.pay(channel,
                request(PaymentProduct.ALIPAY_PAGE, mobileExtra(), null, null)));

        verifyNoInteractions(pageQrClient);
        verify(client).pageUrl(same(channel), eq(PAGE_METHOD), anyMap(), any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("nonOptInExtras")
    void ordinaryPageRequestsRequireBothBooleanFlagsBeforeUsingQrResolver(Map<String, Object> extra) {
        PaymentGatewayProperties.Channel channel = channel(false);
        when(client.pageForm(same(channel), eq(PAGE_METHOD), anyMap(), any())).thenReturn("original-page-form");

        GatewayResponse response = provider.pay(channel, request(PaymentProduct.ALIPAY_PAGE, extra, null, null));

        assertThat(response.code()).isEqualTo("PAGE_FORM_CREATED");
        assertThat(response.redirectHtml()).isEqualTo("original-page-form");
        assertThat(response.qrCode()).isNull();
        ArgumentCaptor<Map<String, Object>> biz = mapCaptor();
        verify(client).pageForm(same(channel), eq(PAGE_METHOD), biz.capture(), any());
        assertThat(biz.getValue()).doesNotContainKeys("cashierMobilePageQr", "qr_pay_mode", "integration_type");
        verifyNoInteractions(pageQrClient);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "ALIPAY_WAP, alipay.trade.wap.pay, QUICK_WAP_WAY, false",
            "ALIPAY_DIRECT_WAP, alipay.trade.wap.pay, QUICK_WAP_WAY, true",
            "ALIPAY_DIRECT_PAGE, alipay.trade.page.pay, FAST_INSTANT_TRADE_PAY, true"
    })
    void otherPageProductsKeepTheirOriginalFormAndParametersEvenWithMobileMarker(
            PaymentProduct product, String method, String productCode, boolean direct) {
        PaymentGatewayProperties.Channel channel = channel(direct);
        Map<String, Object> extra = new LinkedHashMap<>(mobileExtra());
        extra.put("integration_type", "ALIAPP");
        extra.put("qr_pay_mode", "1");
        when(client.pageForm(same(channel), eq(method), anyMap(), any())).thenReturn("original-form");

        GatewayResponse response = provider.pay(channel, request(product, extra, null, null));

        ArgumentCaptor<Map<String, Object>> biz = mapCaptor();
        verify(client).pageForm(same(channel), eq(method), biz.capture(), any());
        assertThat(biz.getValue()).containsEntry("product_code", productCode)
                .containsEntry("integration_type", "ALIAPP").containsEntry("qr_pay_mode", "1")
                .doesNotContainKey("cashierMobilePageQr");
        if (direct) {
            assertThat(biz.getValue()).containsEntry("sub_merchant", Map.of("merchant_id", "SMID-ORIGINAL"));
        }
        assertThat(extra).containsEntry("cashierMobilePageQr", true);
        assertThat(response.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(response.redirectHtml()).isEqualTo("original-form");
        verifyNoInteractions(pageQrClient);
        verifyNoMoreInteractions(client);
    }

    @Test
    void existingTwoArgumentConstructorKeepsOrdinaryPageWalletMode() {
        AlipayPaymentProvider compatibleProvider = new AlipayPaymentProvider(new PaymentGatewayProperties(), client);
        PaymentGatewayProperties.Channel channel = channel(false);
        when(client.pageForm(same(channel), eq(PAGE_METHOD), anyMap(), any())).thenReturn("original-form");

        GatewayResponse response = compatibleProvider.pay(channel, request(PaymentProduct.ALIPAY_PAGE,
                Map.of("cashier", true, "integration_type", "ALIAPP"), null, null));

        ArgumentCaptor<Map<String, Object>> biz = mapCaptor();
        verify(client).pageForm(same(channel), eq(PAGE_METHOD), biz.capture(), any());
        assertThat(biz.getValue()).containsEntry("integration_type", "ALIAPP").doesNotContainKey("qr_pay_mode");
        assertThat(response.redirectHtml()).isEqualTo("original-form");
        verifyNoMoreInteractions(client);
    }

    private static Stream<Map<String, Object>> nonOptInExtras() {
        return Stream.of(null, Map.of(), Map.of("cashier", true), Map.of("cashierMobilePageQr", true),
                Map.of("cashier", false, "cashierMobilePageQr", true),
                Map.of("cashier", true, "cashierMobilePageQr", false),
                Map.of("cashier", "true", "cashierMobilePageQr", true),
                Map.of("cashier", true, "cashierMobilePageQr", "true"));
    }

    private static Map<String, Object> mobileExtra() {
        return Map.of("cashier", true, "cashierMobilePageQr", true);
    }

    private static PayCreateRequest request(PaymentProduct product, Map<String, Object> extra,
                                            Map<String, Object> settle, Map<String, Object> royalty) {
        return new PayCreateRequest(product, "CASHIER-ORIGINAL-001", "Original subject", new BigDecimal("12.34"),
                null, null, null, "https://merchant.example/quit", "10m", "https://merchant.example/notify",
                "https://merchant.example/return", "original-app-auth", null, List.of("ali-original"), extra,
                settle, royalty);
    }

    private static PaymentGatewayProperties.Channel channel(boolean direct) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-original");
        channel.setProvider(direct ? "ALIPAY_DIRECT" : "ALIPAY");
        channel.getAlipay().setSubMerchantId("SMID-ORIGINAL");
        return channel;
    }

    private static void assertPendingWithoutFallback(GatewayResponse response) {
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.code()).isEqualTo("PAGE_QR_UNCONFIRMED");
        assertThat(response.message()).contains("原订单").doesNotContain("sensitive", SIGNED_URL);
        assertThat(response.qrCode()).isNull();
        assertThat(response.redirectHtml()).isNull();
        assertThat(response.redirectUrl()).isNull();
        assertOriginalIdentityAndSafeMetadata(response);
    }

    private static void assertOriginalIdentityAndSafeMetadata(GatewayResponse response) {
        assertThat(response.outTradeNo()).isEqualTo("CASHIER-ORIGINAL-001");
        assertThat(response.channelId()).isEqualTo("ali-original");
        assertThat(response.tradeNo()).isNull();
        assertThat(response.raw()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "request_method", PAGE_METHOD, "request_product_code", "FAST_INSTANT_TRADE_PAY",
                "qr_pay_mode", "4", "integration_type", "PCWEB"));
        assertThat(response.raw().toString()).doesNotContain("sensitive", SIGNED_URL, "original-app-auth");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass((Class) Map.class);
    }
}
