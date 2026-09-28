package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.merchant.api.MerchantNotifyService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.example.payments.sharing.ProfitSharingRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DouyinProfitSharingNotifyControllerTest {
    private static final String ENCRYPT_KEY = "0123456789abcdef0123456789abcdef";
    private static final String NONCE = "testnonce123";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChannelRegistry registry = mock(ChannelRegistry.class);
    private final DouyinPayClient client = mock(DouyinPayClient.class);
    private final MerchantNotifyService merchantNotify = mock(MerchantNotifyService.class);
    private final DemoOrderService orders = new DemoOrderService();
    private final ProfitSharingRecordService records = new ProfitSharingRecordService(orders);
    private final PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
    private DouyinNotifyController controller;

    @BeforeEach
    void setup() {
        channel.setId("dy-main");
        channel.setProvider("DOUYIN");
        channel.getDouyin().setEncryptKey(ENCRYPT_KEY);
        channel.getDouyin().setMchId("MCH");
        channel.getDouyin().setAppId("APP");
        when(registry.find("dy-main")).thenReturn(Optional.of(channel));
        when(client.verifyNotification(eq(channel), any(), any(), any(), any(), anyString())).thenReturn(true);
        orders.recordPaymentCreated("ORDER", "TRANSACTION", "dy-main", "M1", "merchant",
                "DOUYIN_H5", new BigDecimal("12.34"), false, PaymentStatus.SUCCESS);
        orders.recordPaymentCreated("ALI-ORDER", "ALI-TRANSACTION", "ali-main", "M1", "merchant",
                "ALIPAY_PAGE", new BigDecimal("20.00"), false, PaymentStatus.SUCCESS);
        controller = new DouyinNotifyController(registry, client, orders, merchantNotify, mapper, records);
    }

    @Test
    void authenticatedEncryptedWholeSplitUpdatesOnlyMatchingOrderAndIsReplaySafe() throws Exception {
        DemoOrderView before = order("ORDER");
        String body = encrypted("ASYNC_SPLIT.FINISH", "profitsharing", completed());
        assertThat(controller.notify("dy-main", new HttpHeaders(), body).getStatusCode().value()).isEqualTo(200);
        assertThat(order("ORDER").profitShared()).isTrue();
        assertThat(order("ORDER").amount()).isEqualTo(before.amount());
        assertThat(order("ORDER").status()).isEqualTo(before.status());
        assertThat(order("ALI-ORDER").profitShared()).isFalse();
        DemoOrderView first = order("ORDER");
        controller.notify("dy-main", new HttpHeaders(), body);
        assertThat(order("ORDER")).isEqualTo(first);
        assertThat(orders.recent()).hasSize(2);
        assertThat(records.search(null, null, null, null, null)).hasSize(1)
                .allMatch(record -> "SUCCESS".equals(record.status()) && "SPLIT-REQUEST".equals(record.orderNo()));
        verifyNoInteractions(merchantNotify);
    }

    @Test
    void automaticUnfreezeAlongsideSuccessfulReceiversStillRecordsTheSplit() throws Exception {
        Map<String, Object> payload = completed();
        payload.put("finish_amount", 500);
        payload.put("finish_description", "自动解冻");
        payload.put("receivers", List.of(Map.of("account", "receiver-A", "result", "SUCCESS", "amount", 123)));

        controller.notify("dy-main", new HttpHeaders(), encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload));

        assertThat(order("ORDER").profitShared()).isTrue();
        assertThat(records.search(null, null, null, null, null)).hasSize(1).allSatisfy(record -> {
            assertThat(record.status()).isEqualTo("SUCCESS");
            assertThat(record.amount()).isEqualByComparingTo("1.23");
        });
        assertThat(order("ALI-ORDER").profitShared()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"finish_amount,false", "finish_amount,true", "finish_description,false", "finish_description,true"})
    void finishOnlyNotificationNeverCreatesOutgoingSplitOrMarksPayment(String marker, boolean emptyReceivers) throws Exception {
        Map<String, Object> payload = completed();
        payload.remove("receivers");
        if (emptyReceivers) payload.put("receivers", List.of());
        payload.put(marker, "finish_amount".equals(marker) ? 500 : "完结解冻");

        assertThat(controller.notify("dy-main", new HttpHeaders(), encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload))
                .getStatusCode().value()).isEqualTo(200);

        assertThat(records.search(null, null, null, null, null)).isEmpty();
        assertThat(order("ORDER").profitShared()).isFalse();
    }

    @Test
    void malformedReceiversAndFinishFieldStayPendingWithoutMarkingPayment() throws Exception {
        Map<String, Object> payload = completed();
        payload.put("receivers", List.of("invalid"));
        payload.put("finish_amount", 500);

        controller.notify("dy-main", new HttpHeaders(), encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload));

        assertThat(order("ORDER").profitShared()).isFalse();
        assertThat(records.search(null, null, null, null, null)).hasSize(1)
                .allMatch(record -> "PENDING".equals(record.status()) && record.amount() == null);
    }

    @Test
    void pendingFailedPartialOrEmptyReceiversNeverMarkOrder() throws Exception {
        for (Map<String, Object> changed : List.<Map<String, Object>>of(
                Map.of("state", "PROCESSING"), Map.of("state", "FAILED"),
                Map.of("receivers", List.of(Map.of("result", "SUCCESS"), Map.of("result", "CLOSED"))),
                Map.of("receivers", List.of(Map.of("result", "SUCCESS"), Map.of("result", "PENDING"))),
                Map.of("receivers", List.of()), Map.of("receivers", List.of(Map.of("account", "receiver"))))) {
            Map<String, Object> payload = completed();
            payload.putAll(changed);
            controller.notify("dy-main", new HttpHeaders(), encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload));
            assertThat(order("ORDER").profitShared()).isFalse();
        }
    }

    @Test
    void receiverSuccessFinishAndOtherResourceEventsNeverMarkWholeSplit() throws Exception {
        for (String event : List.of("SPLIT.SUCCESS", "PROFITSHARING.FINISH", "PROFITSHARING.UNFREEZE")) {
            controller.notify("dy-main", new HttpHeaders(), encrypted(event, "profitsharing", completed()));
            assertThat(order("ORDER").profitShared()).isFalse();
        }
        controller.notify("dy-main", new HttpHeaders(), encrypted("ASYNC_SPLIT.FINISH", "other", completed()));
        assertThat(order("ORDER").profitShared()).isFalse();
    }

    @Test
    void transactionBelongingToAnotherChannelCannotBeMarked() throws Exception {
        Map<String, Object> payload = completed();
        payload.put("transaction_id", "ALI-TRANSACTION");
        String body = encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload);
        assertThatThrownBy(() -> controller.notify("dy-main", new HttpHeaders(), body))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel does not match");
        assertThat(orders.recent()).allMatch(order -> !order.profitShared());
    }

    @Test
    void unknownTransactionCreatesOnlyAnOutgoingRecord() throws Exception {
        Map<String, Object> payload = completed();
        payload.put("transaction_id", "UNKNOWN");
        String body = encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload);
        assertThat(controller.notify("dy-main", new HttpHeaders(), body).getStatusCode().value()).isEqualTo(200);
        assertThat(orders.recent()).hasSize(2).allMatch(order -> !order.profitShared());
        assertThat(records.search(null, null, "SPLIT-REQUEST", "UNKNOWN", "dy-main")).hasSize(1)
                .allMatch(record -> "SUCCESS".equals(record.status()) && record.amount() == null);
    }

    @Test
    void missingSplitOrTransactionIdentityCannotMarkOrder() throws Exception {
        for (String key : List.of("out_order_no", "transaction_id")) {
            Map<String, Object> payload = completed();
            payload.remove(key);
            String body = encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload);
            assertThatThrownBy(() -> controller.notify("dy-main", new HttpHeaders(), body))
                    .isInstanceOf(GatewayException.class).hasMessageContaining(key);
            assertThat(order("ORDER").profitShared()).isFalse();
        }
    }

    @Test
    void badSignatureIsRejectedBeforeParsingOrUpdatingOrders() {
        when(client.verifyNotification(eq(channel), any(), any(), any(), any(), anyString())).thenReturn(false);
        assertThat(controller.notify("dy-main", new HttpHeaders(), "not even JSON").getStatusCode().value()).isEqualTo(401);
        assertThat(orders.recent()).allMatch(order -> !order.profitShared());
        assertThat(records.search(null, null, null, null, null)).isEmpty();
    }

    @Test
    void alipayChannelCannotEnterDouyinNotifyProcessing() {
        channel.setProvider("ALIPAY");
        assertThat(controller.notify("dy-main", new HttpHeaders(), "invalid").getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(client, merchantNotify);
        assertThat(orders.recent()).allMatch(order -> !order.profitShared());
    }

    @Test
    void encryptedPayloadMustBelongToConfiguredMerchant() throws Exception {
        Map<String, Object> payload = completed();
        payload.put("mchid", "OTHER-MERCHANT");
        String body = encrypted("ASYNC_SPLIT.FINISH", "profitsharing", payload);
        assertThatThrownBy(() -> controller.notify("dy-main", new HttpHeaders(), body))
                .isInstanceOf(GatewayException.class).hasMessageContaining("mchId mismatch");
        assertThat(order("ORDER").profitShared()).isFalse();
    }

    private DemoOrderView order(String outTradeNo) {
        return orders.recent().stream().filter(order -> order.outTradeNo().equals(outTradeNo)).findFirst().orElseThrow();
    }

    private Map<String, Object> completed() {
        return new LinkedHashMap<>(Map.of("transaction_id", "TRANSACTION", "out_order_no", "SPLIT-REQUEST",
                "mchid", "MCH", "appid", "APP", "state", "FINISHED",
                "receivers", List.of(Map.of("account", "A", "result", "SUCCESS"), Map.of("account", "B", "result", "SUCCESS"))));
    }

    private String encrypted(String eventType, String originalType, Map<String, Object> payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ENCRYPT_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("profitsharing".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(mapper.writeValueAsBytes(payload)));
        return mapper.writeValueAsString(Map.of("event_type", eventType, "resource", Map.of(
                "algorithm", "AEAD-AES-256-GCM", "original_type", originalType,
                "associated_data", "profitsharing", "nonce", NONCE, "ciphertext", ciphertext)));
    }
}
