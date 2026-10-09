package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.*;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.ConfirmedReceiptTestSupport;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.sharing.ProfitSharingRelationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentGatewayReceiptTest {
    @Test
    void synchronousAlipayCollectionUsesRealMethodAndQueuesOnceWithoutChangingProduct() {
        Fixture f = new Fixture("ALIPAY");
        PayCreateRequest request = pay("PAY", PaymentProduct.ALIPAY_PAYMENT_CODE);
        when(f.provider.pay(eq(f.channel), any())).thenReturn(alipay(f.channel.getId(), "PAY", "TRADE", "alipay.trade.pay",
                PaymentStatus.SUCCESS, Map.of("code", "10000", "total_amount", "10.00", "trade_no", "TRADE", "out_trade_no", "PAY")));
        f.gateway.pay(request);
        f.gateway.pay(request);
        verify(f.provider, times(2)).pay(f.channel, request);
        assertThat(f.sink.receipts).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alipay.trade.precreate", "alipay.trade.create", "alipay.fund.auth.order.freeze", "alipay.trade.page.pay"})
    void genericCreateOrFreezeSuccessNeverCountsAsCollected(String method) {
        Fixture f = new Fixture("ALIPAY");
        when(f.provider.pay(eq(f.channel), any())).thenReturn(alipay("ali-main", "PAY", "TRADE", method,
                PaymentStatus.SUCCESS, Map.of("code", "10000", "total_amount", "10.00")));
        f.gateway.pay(pay("PAY", PaymentProduct.ALIPAY_PAGE));
        assertThat(f.sink.receipts).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRADE_SUCCESS", "TRADE_FINISHED"})
    void alipayQueryChecksOriginalPaidStateAndCallbackReplayDoesNotQueueAgain(String state) {
        Fixture f = new Fixture("ALIPAY");
        f.create("PAY", false);
        when(f.provider.query(eq(f.channel), any())).thenReturn(alipay("ali-main", "PAY", "TRADE", "alipay.trade.query",
                PaymentStatus.SUCCESS, Map.of("code", "10000", "trade_status", state, "total_amount", "10.00")));
        f.gateway.query(query("PAY", "ali-main"));
        f.orders.recordAlipayNotify("PAY", "TRADE", "ali-main", money("10.00"), "TRADE_SUCCESS", true);
        assertThat(f.sink.receipts).hasSize(1);
    }

    @Test
    void alipayQueryCannotUseGenericSuccessOrMismatchedRawIdentityOrAmount() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("code", "10000"),
                Map.of("code", "10000", "trade_status", "WAIT_BUYER_PAY"),
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "trade_no", "OTHER"),
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "out_trade_no", "OTHER"),
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "9.00"),
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "bad"))) {
            Fixture f = new Fixture("ALIPAY");
            f.create("PAY", false);
            when(f.provider.query(eq(f.channel), any())).thenReturn(alipay("ali-main", "PAY", "TRADE", "alipay.trade.query",
                    PaymentStatus.SUCCESS, body));
            f.gateway.query(query("PAY", "ali-main"));
            assertThat(f.sink.receipts).isEmpty();
        }
    }

    @Test
    void queryAlsoRequiresMatchingLocalChannelTradeNumberAndAmount() {
        for (String mismatch : List.of("channel", "trade", "amount")) {
            Fixture f = new Fixture("ALIPAY");
            f.orders.recordPaymentCreated("PAY", "TRADE", "channel".equals(mismatch) ? "OTHER" : "ali-main",
                    "M1", "Merchant", "Payment", money("amount".equals(mismatch) ? "9.00" : "10.00"), false, PaymentStatus.CREATED);
            when(f.provider.query(eq(f.channel), any())).thenReturn(alipay("ali-main", "PAY", "trade".equals(mismatch) ? "OTHER" : "TRADE",
                    "alipay.trade.query", PaymentStatus.SUCCESS,
                    Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "10.00")));
            f.gateway.query(query("PAY", "ali-main"));
            assertThat(f.sink.receipts).isEmpty();
        }
    }

    @Test
    void douyinQueryAcceptsOriginalSuccessIncludingNestedDataAndUsesFenAmount() {
        for (boolean nested : List.of(false, true)) {
            Fixture f = new Fixture("DOUYIN");
            f.create("PAY", false);
            Map<String, Object> body = Map.of("trade_state", "SUCCESS", "amount", Map.of("total", 1000),
                    "transaction_id", "TRADE", "out_trade_no", "PAY");
            when(f.provider.query(eq(f.channel), any())).thenReturn(douyin("PAY", nested ? Map.of("data", body) : body));
            f.gateway.query(query("PAY", "dy-main"));
            f.gateway.query(query("PAY", "dy-main"));
            assertThat(f.sink.receipts).hasSize(1);
            assertThat(f.sink.receipts.get("PAY").amount()).isEqualByComparingTo("10.00");
        }
    }

    @Test
    void douyinRefundMappedToSuccessNeverQualifiesAndMalformedAmountsCannotNotify() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("trade_state", "REFUND", "amount", Map.of("total", 1000)),
                Map.of("trade_state", "SUCCESS", "amount", Map.of()),
                Map.of("trade_state", "SUCCESS", "amount", Map.of("total", "10.5")),
                Map.of("trade_state", "SUCCESS", "amount", Map.of("total", 900)),
                Map.of("trade_state", "SUCCESS", "amount", "bad"))) {
            Fixture f = new Fixture("DOUYIN");
            f.create("PAY", false);
            when(f.provider.query(eq(f.channel), any())).thenReturn(douyin("PAY", body));
            f.gateway.query(query("PAY", "dy-main"));
            assertThat(f.sink.receipts).isEmpty();
        }
    }

    @Test
    void pendingDouyinCreationAndCancelNeverQueue() {
        Fixture f = new Fixture("DOUYIN");
        when(f.provider.pay(eq(f.channel), any())).thenReturn(new GatewayResponse("dy-main", PaymentStatus.PENDING,
                "SUCCESS", "created", "PAY", "TRADE", null, null, Map.of("trade_state", "SUCCESS"), List.of()));
        f.gateway.pay(pay("PAY", PaymentProduct.DOUYIN_H5));
        when(f.provider.cancel(eq(f.channel), any())).thenReturn(douyin("PAY", Map.of("trade_state", "SUCCESS")));
        f.gateway.cancel(new PaymentCancelRequest("PAY", "TRADE", null, List.of("dy-main"), Map.of()));
        assertThat(f.sink.receipts).isEmpty();
    }

    @Test
    void successfulCaptureUsesActualPartialAmountAndParentOrder() {
        Fixture f = new Fixture("ALIPAY");
        f.create("AUTH", true);
        when(f.provider.preauthCapture(eq(f.channel), any())).thenReturn(alipay("ali-main", "AUTH_PAY_1", "CAPTURE",
                "alipay.trade.pay", PaymentStatus.SUCCESS, Map.of("code", "10000", "total_amount", "3.00")));
        f.gateway.preauthCapture(capture());
        assertThat(f.sink.receipts.keySet()).containsExactly("AUTH");
        assertThat(f.sink.receipts.get("AUTH").amount()).isEqualByComparingTo("3.00");
        assertThat(f.orders.view("AUTH").amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void pendingCaptureDoesNotNotifyButExactChildQueryOrCallbackCanLaterCompleteItOnce() {
        Fixture f = new Fixture("ALIPAY");
        f.create("AUTH", true);
        when(f.provider.preauthCapture(eq(f.channel), any())).thenReturn(alipay("ali-main", "AUTH_PAY_1", null,
                "alipay.trade.pay", PaymentStatus.PENDING, Map.of("code", "10003")));
        f.gateway.preauthCapture(capture());
        assertThat(f.orders.view("AUTH").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(f.sink.receipts).isEmpty();
        assertThat(f.sink.captures.keySet()).containsExactly("AUTH_PAY_1");
        when(f.provider.query(eq(f.channel), any())).thenReturn(alipay("ali-main", "AUTH_PAY_1", "CAPTURE",
                "alipay.trade.query", PaymentStatus.SUCCESS,
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "3.00")));
        f.gateway.query(query("AUTH_PAY_1", "ali-main"));
        f.orders.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("3.00"), "TRADE_SUCCESS", true);
        assertThat(f.sink.receipts).hasSize(1);
        assertThat(f.sink.receipts.get("AUTH").tradeNo()).isEqualTo("CAPTURE");
        assertThat(f.sink.receipts.get("AUTH").amount()).isEqualByComparingTo("3.00");
    }

    @Test
    void successfulChildQueryCannotOpenUnmarkedHistoricalCapture() {
        Fixture f = new Fixture("ALIPAY");
        f.create("AUTH", true);
        f.orders.convertPreauthToPay("AUTH", "CAPTURE");
        when(f.provider.query(eq(f.channel), any())).thenReturn(alipay("ali-main", "AUTH_PAY_1", "CAPTURE",
                "alipay.trade.query", PaymentStatus.SUCCESS,
                Map.of("code", "10000", "trade_status", "TRADE_SUCCESS", "total_amount", "3.00")));
        f.gateway.query(query("AUTH_PAY_1", "ali-main"));
        assertThat(f.sink.receipts).isEmpty();
    }

    private static class Fixture {
        final ConfirmedReceiptTestSupport.MemorySink sink = new ConfirmedReceiptTestSupport.MemorySink();
        final DemoOrderService orders = ConfirmedReceiptTestSupport.orders(sink);
        final PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        final PaymentProvider provider = mock(PaymentProvider.class);
        final PaymentGatewayService gateway;

        Fixture(String providerCode) {
            PaymentGatewayProperties properties = new PaymentGatewayProperties();
            properties.getRouting().setMaxAttempts(1);
            properties.getRouting().setFailover(false);
            channel.setId("DOUYIN".equals(providerCode) ? "dy-main" : "ali-main");
            channel.setProvider(providerCode);
            channel.setEnabled(true);
            channel.setDailyEnabled(true);
            ChannelRegistry registry = mock(ChannelRegistry.class);
            when(registry.all()).thenReturn(List.of(channel));
            when(registry.isEnabled(channel)).thenReturn(true);
            when(registry.find(channel.getId())).thenReturn(java.util.Optional.of(channel));
            when(provider.providerCode()).thenReturn(providerCode);
            gateway = new PaymentGatewayService(properties, new ChannelSelector(registry), List.of(provider), orders,
                    mock(DemoMerchantService.class), mock(OnboardingRecordService.class), mock(ComplaintRecordService.class),
                    mock(ProfitSharingRelationService.class));
        }

        void create(String outTradeNo, boolean preauth) {
            orders.recordPaymentCreated(outTradeNo, preauth ? "AUTH-NO" : null, channel.getId(), "M1", "Merchant",
                    "Payment", money("10.00"), preauth, preauth ? PaymentStatus.SUCCESS : PaymentStatus.CREATED);
        }
    }

    private static PayCreateRequest pay(String outTradeNo, PaymentProduct product) {
        return new PayCreateRequest(product, outTradeNo, "Payment", money("10.00"), null, null, null, null,
                null, null, null, null, null, null, Map.of(), null, null);
    }

    private static PaymentQueryRequest query(String outTradeNo, String channel) {
        return new PaymentQueryRequest(outTradeNo, null, null, List.of(channel), Map.of());
    }

    private static PreauthCaptureRequest capture() {
        return new PreauthCaptureRequest("AUTH", "AUTH_PAY_1", "AUTH-NO", "Payment", money("3.00"), null, null,
                "COMPLETE", null, List.of("ali-main"), Map.of());
    }

    private static GatewayResponse alipay(String channel, String outTradeNo, String tradeNo, String method,
                                         PaymentStatus status, Map<String, Object> body) {
        return new GatewayResponse(channel, status, String.valueOf(body.get("code")), "Result", outTradeNo, tradeNo,
                null, null, Map.of("request_method", method, method.replace('.', '_') + "_response", body), List.of());
    }

    private static GatewayResponse douyin(String outTradeNo, Map<String, Object> body) {
        Map<String, Object> raw = new LinkedHashMap<>(body);
        raw.put("request_path", "/v1/trade/transactions/out-trade-no/" + outTradeNo + "?mchid=MCH");
        return new GatewayResponse("dy-main", PaymentStatus.SUCCESS, "SUCCESS", "Result", outTradeNo, "TRADE",
                null, null, raw, List.of());
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }
}
