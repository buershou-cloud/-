package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.merchant.api.MerchantNotifyService;
import com.example.payments.order.ConfirmedReceiptSink;
import com.example.payments.order.ConfirmedReceiptTestSupport;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.sharing.ProfitSharingRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfirmedReceiptNotifyTest {
    private static final String ENCRYPT_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NONCE = "testnonce123";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void verifiedAlipayPaidCallbackQueuesOnceAndWaitingOrFinishedReplaysDoNot() {
        Fixture f = new Fixture("ALIPAY");
        f.create("PAY", false);
        assertThat(f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "WAIT_BUYER_PAY", "10.00", "APP"))
                .getBody()).isEqualTo("success");
        assertThat(f.sink.receipts).isEmpty();
        f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "TRADE_SUCCESS", "10.00", "APP"));
        f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "WAIT_BUYER_PAY", "10.00", "APP"));
        f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "TRADE_FINISHED", "10.00", "APP"));
        assertThat(f.sink.receipts).hasSize(1);
        assertThat(f.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    @Test
    void alipayNotificationAppMustMatchEvenWhenSignatureIsValid() {
        for (String app : List.of("OTHER-APP", "")) {
            Fixture f = new Fixture("ALIPAY");
            f.create("PAY", false);
            assertThat(f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "TRADE_SUCCESS", "10.00", app))
                    .getBody()).isEqualTo("success");
            assertThat(f.sink.receipts).isEmpty();
            assertThat(f.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        }
    }

    @Test
    void alipayPreauthSuccessIsFreezeButRealCaptureChildNotifiesActualCollectedAmount() {
        Fixture f = new Fixture("ALIPAY");
        f.create("AUTH", true);
        MultiValueMap<String, String> freeze = new LinkedMultiValueMap<>();
        freeze.add("out_order_no", "AUTH");
        freeze.add("auth_no", "AUTH-NO");
        freeze.add("amount", "10.00");
        freeze.add("status", "SUCCESS");
        freeze.add("app_id", "APP");
        f.alipay.notify("ali-main", freeze);
        assertThat(f.orders.view("AUTH").status()).isEqualTo(DemoOrderStatus.FROZEN);
        assertThat(f.sink.receipts).isEmpty();
        f.alipay.notify("ali-main", aliForm("AUTH_PAY_1", "CAPTURE", "TRADE_SUCCESS", "3.00", "APP"));
        assertThat(f.sink.receipts.keySet()).containsExactly("AUTH");
        assertThat(f.sink.receipts.get("AUTH").amount()).isEqualByComparingTo("3.00");
    }

    @Test
    void rejectedAlipaySignatureNeverUpdatesOrQueues() {
        Fixture f = new Fixture("ALIPAY");
        f.create("PAY", false);
        when(f.alipayClient.verifyNotify(eq(f.channel), anyMap())).thenReturn(false);
        assertThat(f.alipay.notify("ali-main", aliForm("PAY", "TRADE", "TRADE_SUCCESS", "10.00", "APP"))
                .getBody()).isEqualTo("failure");
        assertThat(f.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(f.sink.receipts).isEmpty();
    }

    @Test
    void encryptedVerifiedDouyinSuccessQueuesOnceAndQueryReplayDoesNot() throws Exception {
        Fixture f = new Fixture("DOUYIN");
        f.create("PAY", false);
        String body = encrypted(dyPayload("SUCCESS"));
        assertThat(f.douyin.notify("dy-main", new HttpHeaders(), body).getStatusCode().value()).isEqualTo(200);
        f.douyin.notify("dy-main", new HttpHeaders(), body);
        f.orders.recordPaymentResult("PAY", "TRADE", "dy-main", PaymentStatus.SUCCESS, true, money("10.00"));
        assertThat(f.sink.receipts).hasSize(1);
    }

    @Test
    void douyinRawRefundSuccessMappingNeverCountsAsCollection() throws Exception {
        Fixture f = new Fixture("DOUYIN");
        f.create("PAY", false);
        assertThat(f.douyin.notify("dy-main", new HttpHeaders(), encrypted(dyPayload("REFUND")))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(f.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(f.sink.receipts).isEmpty();
    }

    @Test
    void douyinMalformedAmountWrongLocalChannelOrMissingMerchantMetadataCannotNotify() throws Exception {
        for (String mismatch : List.of("amount", "missing-total", "channel", "appid", "mchid")) {
            Fixture f = new Fixture("DOUYIN");
            f.orders.recordPaymentCreated("PAY", null, "channel".equals(mismatch) ? "OTHER" : "dy-main",
                    "M1", "Merchant", "Payment", money("10.00"), false, PaymentStatus.CREATED);
            Map<String, Object> payload = dyPayload("SUCCESS");
            if ("amount".equals(mismatch)) payload.put("amount", Map.of("total", 900));
            if ("missing-total".equals(mismatch)) payload.put("amount", Map.of());
            if ("appid".equals(mismatch) || "mchid".equals(mismatch)) payload.remove(mismatch);
            assertThat(f.douyin.notify("dy-main", new HttpHeaders(), encrypted(payload)).getStatusCode().value()).isEqualTo(200);
            assertThat(f.sink.receipts).isEmpty();
        }
    }

    @Test
    void rejectedDouyinSignatureCannotProcessPayload() {
        Fixture f = new Fixture("DOUYIN");
        f.create("PAY", false);
        when(f.douyinClient.verifyNotification(eq(f.channel), any(), any(), any(), any(), anyString())).thenReturn(false);
        assertThat(f.douyin.notify("dy-main", new HttpHeaders(), "invalid").getStatusCode().value()).isEqualTo(401);
        assertThat(f.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(f.sink.receipts).isEmpty();
    }

    @Test
    void notificationFailureNeverChangesSuccessfulUpstreamAcknowledgement() throws Exception {
        ConfirmedReceiptSink failing = order -> { throw new IllegalStateException("test queue unavailable"); };
        Fixture ali = new Fixture("ALIPAY", failing);
        ali.create("PAY", false);
        assertThat(ali.alipay.notify("ali-main", aliForm("PAY", "TRADE", "TRADE_SUCCESS", "10.00", "APP"))
                .getBody()).isEqualTo("success");
        Fixture dy = new Fixture("DOUYIN", failing);
        dy.create("PAY", false);
        assertThat(dy.douyin.notify("dy-main", new HttpHeaders(), encrypted(dyPayload("SUCCESS")))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(ali.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(dy.orders.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    private static final class Fixture {
        final ConfirmedReceiptTestSupport.MemorySink sink = new ConfirmedReceiptTestSupport.MemorySink();
        final DemoOrderService orders;
        final PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        final AlipayOpenApiClient alipayClient = mock(AlipayOpenApiClient.class);
        final DouyinPayClient douyinClient = mock(DouyinPayClient.class);
        final AlipayNotifyController alipay;
        final DouyinNotifyController douyin;

        Fixture(String provider) { this(provider, null); }

        Fixture(String provider, ConfirmedReceiptSink providedSink) {
            orders = ConfirmedReceiptTestSupport.orders(providedSink == null ? sink : providedSink);
            channel.setId("DOUYIN".equals(provider) ? "dy-main" : "ali-main");
            channel.setProvider(provider);
            channel.getAlipay().setAppId("APP");
            channel.getDouyin().setAppId("APP");
            channel.getDouyin().setMchId("MCH");
            channel.getDouyin().setEncryptKey(ENCRYPT_KEY);
            ChannelRegistry registry = mock(ChannelRegistry.class);
            when(registry.find(channel.getId())).thenReturn(Optional.of(channel));
            when(alipayClient.verifyNotify(eq(channel), anyMap())).thenReturn(true);
            when(douyinClient.verifyNotification(eq(channel), any(), any(), any(), any(), anyString())).thenReturn(true);
            MerchantNotifyService merchantNotify = mock(MerchantNotifyService.class);
            alipay = new AlipayNotifyController(registry, alipayClient, orders, merchantNotify);
            douyin = new DouyinNotifyController(registry, douyinClient, orders, merchantNotify, JSON, new ProfitSharingRecordService(orders));
        }

        void create(String orderNo, boolean preauth) {
            orders.recordPaymentCreated(orderNo, preauth ? "AUTH-NO" : null, channel.getId(), "M1", "Merchant", "Payment",
                    money("10.00"), preauth, preauth ? PaymentStatus.SUCCESS : PaymentStatus.CREATED);
        }
    }

    private static MultiValueMap<String, String> aliForm(String outTradeNo, String tradeNo, String status, String amount, String appId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("out_trade_no", outTradeNo);
        form.add("trade_no", tradeNo);
        form.add("trade_status", status);
        form.add("total_amount", amount);
        if (!appId.isEmpty()) form.add("app_id", appId);
        return form;
    }

    private static Map<String, Object> dyPayload(String state) {
        return new LinkedHashMap<>(Map.of("out_trade_no", "PAY", "transaction_id", "TRADE", "trade_state", state,
                "appid", "APP", "mchid", "MCH", "amount", Map.of("total", 1000)));
    }

    private static String encrypted(Map<String, Object> payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(JSON.writeValueAsBytes(payload)));
        return JSON.writeValueAsString(Map.of("event_type", "TRANSACTION.SUCCESS", "resource", Map.of(
                "algorithm", "AEAD-AES-256-GCM", "original_type", "transaction", "associated_data", "transaction",
                "nonce", NONCE, "ciphertext", ciphertext)));
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }
}
