package com.example.payments.merchant.api;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.*;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.MerchantRouting;
import com.example.payments.order.DemoOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantApiControllerTest {
    private final DemoOrderService orders = new DemoOrderService();
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final DemoMerchantService merchants = mock(DemoMerchantService.class);
    private final MerchantSignatureService signatures = mock(MerchantSignatureService.class);
    private final MerchantNotifyService notifications = mock(MerchantNotifyService.class);
    private final DemoMerchantView merchant = new DemoMerchantView("M1", "Merchant", BigDecimal.ZERO, "0%", "正常",
            BigDecimal.ZERO, "0", "", "secret", "public", "public", "private", "MD5_RSA2",
            Set.of("ali-one", "ali-two"), RoutingMode.ROUND_ROBIN, "Round robin");
    private MerchantApiController controller;
    private ChannelRegistry registry;

    @BeforeEach
    void setup() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.setChannels(List.of(channel("ali-one", "ALIPAY"), channel("ali-two", "ALIPAY"), channel("dy", "DOUYIN")));
        registry = new ChannelRegistry(properties);
        controller = new MerchantApiController(gateway, merchants, orders, signatures, registry,
                new ChannelSelector(registry), notifications);
        when(merchants.routing("M1")).thenReturn(new MerchantRouting(merchant.channelIds(), RoutingMode.ROUND_ROBIN));
        when(signatures.verify(any())).thenReturn(merchant);
        when(signatures.success(any(), anyString(), any())).thenAnswer(call ->
                new MerchantApiResponse<>("SUCCESS", "OK", call.getArgument(2), "time", "MD5", "signature"));
        when(signatures.successCanonical(any(), anyString(), any())).thenAnswer(call ->
                new MerchantApiResponse<>("SUCCESS", "OK", call.getArgument(2), "time", "MD5", "canonical-signature"));
    }

    @Test
    void checkReturnsOnlyNonSecretConfigurationAndUsesCanonicalSignature() {
        MerchantApiResponse<?> response = controller.check(new MerchantApiCheckRequest("M1", "MD5", "time", "nonce", "signature"));
        Map<?, ?> data = (Map<?, ?>) response.data();
        assertThat(data.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("merchantId", "serverTime", "signMode", "channelIds", "readiness");
        Map<?, ?> readiness = (Map<?, ?>) data.get("readiness");
        assertThat(readiness.get("availableChannelCount")).isEqualTo(2);
        assertThat(((List<?>) readiness.get("products")).stream().map(Object::toString).toList()).contains(PaymentProduct.ALIPAY_WAP.name()).doesNotContain(PaymentProduct.DOUYIN_H5.name());
        assertThat(response.sign()).isEqualTo("canonical-signature");
        verifyNoInteractions(gateway);
    }

    @Test
    void checkWithoutBindingsDiagnosesZeroChannelsAndPayIsBlocked() {
        when(merchants.routing("M1")).thenReturn(new MerchantRouting(Set.of(), RoutingMode.ROUND_ROBIN));
        Map<?, ?> data = (Map<?, ?>) controller.check(new MerchantApiCheckRequest("M1", "MD5", "time", "nonce", "signature")).data();
        assertThat(((Map<?, ?>) data.get("readiness")).get("availableChannelCount")).isEqualTo(0);
        assertCode("CHANNEL_FORBIDDEN", () -> controller.pay(pay("NEW", null, Map.of(), "1.00")));
        verifyNoInteractions(gateway);
    }

    @Test
    void signedPayPinsOneChannelAndStoresOriginalCallbackBeforeUpstream() {
        when(gateway.pay(any())).thenAnswer(call -> {
            PayCreateRequest request = call.getArgument(0);
            assertThat(request.channelIds()).hasSize(1);
            assertThat(orders.view("NEW").channelId()).isEqualTo(request.channelIds().getFirst());
            assertThat(orders.merchantNotifyTarget("NEW").orElseThrow().notifyUrl()).isEqualTo("https://merchant.example/notify");
            assertThat(request.extra()).containsEntry("merchantApiRequest", true).containsEntry("merchantId", "M1");
            return result(PaymentStatus.CREATED);
        });
        assertThat(controller.pay(pay("NEW", null, Map.of(), "1.00")).sign()).isEqualTo("signature");
        assertCode("ORDER_CONFLICT", () -> controller.pay(pay("NEW", List.of("ali-two"), Map.of(), "1.00")));
        verify(gateway, times(1)).pay(any());
    }

    @Test
    void concurrentIdenticalOrderCannotReachUpstreamTwice() throws Exception {
        when(gateway.pay(any())).thenReturn(result(PaymentStatus.CREATED));
        Callable<String> submit = () -> {
            try { controller.pay(pay("RACE", null, Map.of(), "1.00")); return "OK"; }
            catch (MerchantApiException ex) { return ex.code(); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(submit, submit));
            assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder("OK", "ORDER_CONFLICT");
        }
        verify(gateway, times(1)).pay(any());
    }

    @Test
    void uncertainPaymentDoesNotAllowSecondAttemptOnAnotherChannel() {
        when(gateway.pay(any())).thenThrow(new IllegalStateException("Upstream timed out"));
        assertThatThrownBy(() -> controller.pay(pay("TIMEOUT", List.of("ali-one"), Map.of(), "1.00"))).hasMessage("Upstream timed out");
        assertCode("ORDER_CONFLICT", () -> controller.pay(pay("TIMEOUT", List.of("ali-two"), Map.of(), "1.00")));
        assertThat(orders.view("TIMEOUT").channelId()).isEqualTo("ali-one");
        verify(gateway, times(1)).pay(any());
    }

    @Test
    void rejectsForeignOrderAndEveryUnauthorizedChannelBeforePay() {
        seed("EXISTING", "T-FOREIGN", "M2");
        assertCode("ORDER_CONFLICT", () -> controller.pay(pay("EXISTING", null, Map.of(), "1.00")));
        assertCode("CHANNEL_FORBIDDEN", () -> controller.pay(pay("NEW", List.of("ali-one", "dy"), Map.of(), "1.00")));
        assertThat(orders.view("EXISTING").merchantId()).isEqualTo("M2");
        verifyNoInteractions(gateway);
    }

    @Test
    void reservedExtraAndFractionalCentAmountsNeverReachUpstream() {
        for (String key : List.of("out_trade_no", "total_amount", "merchantId", "notify_url", "alipay_method", "preauth_method")) {
            assertCode("RESERVED_EXTRA_FIELD", () -> controller.pay(pay("NEW", null, Map.of(key, "override"), "1.00")));
        }
        assertCode("INVALID_AMOUNT", () -> controller.pay(pay("NEW", null, Map.of(), "1.001")));
        verifyNoInteractions(gateway);
    }

    @Test
    void invalidMerchantCallbackNeverReservesOrChargesOrder() {
        for (String callback : List.of("/relative", "file:///tmp/callback", "https://user:secret@example.com/notify", "https://example.com/notify#part")) {
            assertCode("INVALID_NOTIFY_URL", () -> controller.pay(payWithNotify("URL", null, Map.of(), "1.00", callback)));
        }
        assertThat(orders.recent()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void requestCannotReplaceBoundChannelAuthorizationToken() {
        MerchantApiPayRequest request = new MerchantApiPayRequest("M1", PaymentProduct.ALIPAY_WAP, "AUTH", "Item", BigDecimal.ONE,
                null, null, null, null, null, null, null, "foreign-token", null, null, null,
                null, null, "MD5", "time", "nonce", "sign");
        assertCode("RESERVED_APP_AUTH_TOKEN", () -> controller.pay(request));
        assertThat(orders.recent()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void missingGatewayCallbackIsDiagnosedWithoutReservingOrder() {
        registry.find("ali-one").orElseThrow().getAlipay().setNotifyUrl(null);
        Map<?, ?> data = (Map<?, ?>) controller.check(new MerchantApiCheckRequest("M1", "MD5", "time", "nonce", "sign")).data();
        Map<?, ?> readiness = (Map<?, ?>) data.get("readiness");
        assertThat(readiness.get("availableChannelCount")).isEqualTo(1);
        assertThat((List<?>) readiness.get("configurationWarnings")).hasSize(1);
        assertCode("CHANNEL_NOT_READY", () -> controller.pay(pay("NOTREADY", List.of("ali-one"), Map.of(), "1.00")));
        assertThat(orders.recent()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void queryByTradeNoResolvesOwnedOrderAndPinsOriginalChannel() {
        seed("OWN", "T-OWN", "M1");
        when(gateway.query(any())).thenReturn(result(PaymentStatus.SUCCESS));
        controller.query(query(null, "T-OWN", null));
        ArgumentCaptor<PaymentQueryRequest> captured = ArgumentCaptor.forClass(PaymentQueryRequest.class);
        verify(gateway).query(captured.capture());
        assertThat(captured.getValue().outTradeNo()).isEqualTo("OWN");
        assertThat(captured.getValue().tradeNo()).isEqualTo("T-OWN");
        assertThat(captured.getValue().channelIds()).containsExactly("ali-one");
        verify(notifications).notifyPayment(any(), eq("TRADE_SUCCESS"));
    }

    @Test
    void conflictingIdentifiersForeignOwnershipAndChannelChangesAreBlocked() {
        seed("OWN", "T-OWN", "M1");
        seed("OTHER", "T-OTHER", "M2");
        assertCode("ORDER_IDENTIFIER_MISMATCH", () -> controller.query(query("OWN", "T-OTHER", null)));
        assertCode("ORDER_NOT_FOUND", () -> controller.query(query(null, "T-OTHER", null)));
        assertCode("INVALID_ORDER_ID", () -> controller.query(query(null, null, null)));
        assertCode("CHANNEL_FORBIDDEN", () -> controller.query(query("OWN", null, List.of("ali-two"))));
        verifyNoInteractions(gateway);
    }

    @Test
    void cancelAndRefundByTradeNoForwardCanonicalOrderAndChannel() {
        seed("OWN", "T-OWN", "M1");
        when(gateway.cancel(any())).thenReturn(result(PaymentStatus.CLOSED));
        when(gateway.refund(any())).thenReturn(result(PaymentStatus.PENDING));
        controller.cancel(new MerchantApiCancelRequest("M1", null, "T-OWN", null, "MD5", "time", "nonce", "sign"));
        controller.refund(refund(null, "T-OWN", "REFUND1", "1.00"));
        verify(gateway).cancel(argThat(request -> request.outTradeNo().equals("OWN") && request.channelIds().equals(List.of("ali-one"))));
        verify(gateway).refund(argThat(request -> request.outTradeNo().equals("OWN") && request.channelIds().equals(List.of("ali-one"))));
        assertCode("REFUND_CONFLICT", () -> controller.refund(refund("OWN", null, "REFUND1", "1.00")));
        verify(gateway, times(1)).refund(any());
    }

    @Test
    void explicitCanonicalHeaderOptInPreservesLegacyDefaultAndRejectsUnknownModeBeforePayment() {
        when(gateway.pay(any())).thenReturn(result(PaymentStatus.CREATED));
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Merchant-Response-Signature", "canonical-v1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        try {
            assertThat(controller.pay(pay("CANONICAL", null, Map.of(), "1.00")).sign()).isEqualTo("canonical-signature");
            http.removeHeader("X-Merchant-Response-Signature");
            http.addHeader("X-Merchant-Response-Signature", "unsupported");
            assertCode("UNSUPPORTED_RESPONSE_SIGNATURE", () -> controller.pay(pay("UNKNOWN", null, Map.of(), "1.00")));
        } finally { RequestContextHolder.resetRequestAttributes(); }
        verify(gateway, times(1)).pay(any());
    }

    private void seed(String order, String trade, String owner) {
        orders.recordPaymentCreated(order, trade, "ali-one", owner, "Merchant", "ALIPAY_WAP", new BigDecimal("10"), false, PaymentStatus.SUCCESS);
    }

    private static MerchantApiPayRequest pay(String order, List<String> channels, Map<String, Object> extra, String amount) {
        return payWithNotify(order, channels, extra, amount, "https://merchant.example/notify");
    }

    private static MerchantApiPayRequest payWithNotify(String order, List<String> channels, Map<String, Object> extra, String amount, String notify) {
        return new MerchantApiPayRequest("M1", PaymentProduct.ALIPAY_WAP, order, "Item", new BigDecimal(amount),
                null, null, null, null, null, notify, null, null, null, channels, extra,
                null, null, "MD5", "time", "nonce", "sign");
    }

    private static MerchantApiQueryRequest query(String order, String trade, List<String> channels) {
        return new MerchantApiQueryRequest("M1", order, trade, channels, "MD5", "time", "nonce", "sign");
    }

    private static MerchantApiRefundRequest refund(String order, String trade, String requestNo, String amount) {
        return new MerchantApiRefundRequest("M1", order, trade, new BigDecimal(amount), requestNo, null, null, "MD5", "time", "nonce", "sign");
    }

    private static GatewayResponse result(PaymentStatus status) {
        return new GatewayResponse("ali-one", status, "OK", "OK", "OWN", "T-OWN", null, null, Map.of(), List.of());
    }

    private static PaymentGatewayProperties.Channel channel(String id, String provider) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId(id); channel.setProvider(provider);
        channel.getAlipay().setNotifyUrl("https://gateway.example/notify/alipay/" + id);
        channel.getDouyin().setNotifyUrl("https://gateway.example/notify/douyin/" + id);
        return channel;
    }

    private static void assertCode(String code, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
