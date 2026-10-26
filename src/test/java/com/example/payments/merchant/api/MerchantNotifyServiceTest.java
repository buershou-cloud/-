package com.example.payments.merchant.api;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantNotifyServiceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock initialTime = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);
    private final DemoOrderService orders = mock(DemoOrderService.class);
    private final DemoMerchantService merchants = mock(DemoMerchantService.class);
    private final MerchantSignatureService signatures = mock(MerchantSignatureService.class);
    private final MerchantCallbackTransport client = mock(MerchantCallbackTransport.class);

    @Test
    void persistsBeforeDispatchAndRestartRetriesWhenWorkerWasNeverRun() throws Exception {
        prepare();
        var queued = new ArrayDeque<Runnable>();
        var service = new MerchantNotifyService(orders, merchants, signatures, json, directory, client,
                initialTime, queued::add);
        service.notifyPayment(order(), "TRADE_SUCCESS");

        assertThat(delivery().path("attempts").asInt()).isZero();
        assertThat(delivery().path("delivered").asBoolean()).isFalse();
        assertThat(queued).hasSize(1);
        verify(client, never()).post(any(), any());
        when(client.post(any(), any())).thenReturn(response(200, "success"));
        service(initialTime).retryPending();
        assertThat(delivery().path("delivered").asBoolean()).isTrue();
    }

    @Test
    void blockedCallbackDoesNotDelayCallerOrDuplicateNotificationAcrossInstances() throws Exception {
        prepare();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor();
        when(client.post(any(), any())).thenAnswer(call -> {
            assertThat(delivery().path("attempts").asInt()).isZero();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test callback timed out");
            return response(200, "success");
        });
        var service = new MerchantNotifyService(orders, merchants, signatures, json, directory, client,
                initialTime, workers);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> service.notifyPayment(order(), "TRADE_SUCCESS"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                service.notifyPayment(order(), "TRADE_SUCCESS");
                service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");
                service(initialTime).retryPending();
            });
            verify(client, times(1)).post(any(), any());
        } finally {
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(delivery().path("delivered").asBoolean()).isTrue();
    }

    @Test
    void fullDispatchQueueLeavesDurableRetryWithoutSendingOnCallerThread() throws Exception {
        prepare();
        var service = new MerchantNotifyService(orders, merchants, signatures, json, directory, client,
                initialTime, task -> { throw new RejectedExecutionException("full"); });
        service.notifyPayment(order(), "TRADE_SUCCESS");
        assertThat(delivery().path("attempts").asInt()).isZero();
        verify(client, never()).post(any(), any());
        when(client.post(any(), any())).thenReturn(response(200, "success"));
        service(initialTime).retryPending();
        assertThat(delivery().path("delivered").asBoolean()).isTrue();
    }

    @Test
    void notifiesWithoutJdbcAndDeduplicatesAcknowledgedStateAcrossRestart() throws Exception {
        prepare();
        MerchantCallbackTransport.Result acknowledged = response(200, " success\n");
        when(client.post(any(), any())).thenReturn(acknowledged);
        MerchantNotifyService service = service(initialTime);

        service.notifyPayment(order(), "TRADE_SUCCESS");
        service.notifyPayment(order(), "TRADE_FINISHED");
        service(Clock.offset(initialTime, Duration.ofDays(1))).notifyPayment(order(), "TRADE_SUCCESS");

        verify(client, times(1)).post(any(), any());
        JsonNode persisted = delivery();
        assertThat(persisted.path("delivered").asBoolean()).isTrue();
        assertThat(persisted.path("payload").path("totalAmount").asText()).isEqualTo("1.00");
        assertThat(persisted.path("payload").path("sign").asText()).isEqualTo("signature");
        assertThat(persisted.path("payload").has("parentOutTradeNo")).isFalse();
        assertThat(persisted.toString()).doesNotContain("md5Key", "privateKey");
        ArgumentCaptor<URI> request = ArgumentCaptor.forClass(URI.class);
        verify(client).post(request.capture(), any());
        assertThat(request.getValue().toString()).isEqualTo("https://merchant.example/notify");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void captureNotificationSignsActualChildAmountAndKeepsParentLinkAcrossDeliveryRestart() throws Exception {
        prepare();
        DemoOrderView captured = order();
        when(captured.outTradeNo()).thenReturn("CAPTURE-20");
        when(captured.amount()).thenReturn(new BigDecimal("20.00"));
        when(orders.merchantNotifyTarget("CAPTURE-20"))
                .thenReturn(Optional.of(new DemoOrderService.MerchantNotifyTarget("M1", "https://merchant.example/notify")));
        when(orders.captureParentOutTradeNo("CAPTURE-20")).thenReturn("AUTH-100");
        MerchantCallbackTransport.Result unavailable = response(503, "retry");
        MerchantCallbackTransport.Result acknowledged = response(200, "success");
        when(client.post(any(), any()))
                .thenReturn(unavailable, acknowledged);

        service(initialTime).notifyPayment(captured, "TRADE_SUCCESS");

        JsonNode payload = delivery().path("payload");
        assertThat(payload.path("outTradeNo").asText()).isEqualTo("CAPTURE-20");
        assertThat(payload.path("parentOutTradeNo").asText()).isEqualTo("AUTH-100");
        assertThat(payload.path("totalAmount").asText()).isEqualTo("20.00");
        assertThat(payload.path("status").asText()).isEqualTo("COMPLETED");

        when(orders.merchantNotifyTarget("CAPTURE-20")).thenReturn(Optional.empty());
        when(orders.captureParentOutTradeNo("CAPTURE-20")).thenReturn(null);
        service(Clock.offset(initialTime, Duration.ofSeconds(6))).retryPending();

        assertThat(delivery().path("delivered").asBoolean()).isTrue();
        assertThat(delivery().path("payload").path("parentOutTradeNo").asText()).isEqualTo("AUTH-100");
        ArgumentCaptor<Map<String, Object>> signedPayloads = ArgumentCaptor.forClass((Class) Map.class);
        verify(signatures, times(2)).signForMerchant(any(), eq("MD5"), signedPayloads.capture());
        assertThat(signedPayloads.getAllValues()).allSatisfy(signed -> assertThat(signed)
                .containsEntry("outTradeNo", "CAPTURE-20")
                .containsEntry("parentOutTradeNo", "AUTH-100")
                .containsEntry("totalAmount", "20.00"));
        verify(orders, times(1)).captureParentOutTradeNo("CAPTURE-20");
    }

    @Test
    void failedDeliveryIsAlreadyOnDiskThenRetriesAfterRestartWithoutOrderLookup() throws Exception {
        prepare();
        MerchantCallbackTransport.Result acknowledged = response(200, "success");
        when(client.post(any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(delivery().path("attempts").asInt()).isZero();
                    throw new IOException("offline");
                }).thenReturn(acknowledged);
        service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");
        assertThat(delivery().path("delivered").asBoolean()).isFalse();
        assertThat(delivery().path("attempts").asInt()).isEqualTo(1);

        when(orders.merchantNotifyTarget("ORDER")).thenReturn(Optional.empty());
        service(Clock.offset(initialTime, Duration.ofSeconds(4))).retryPending();
        verify(client, times(1)).post(any(), any());
        service(Clock.offset(initialTime, Duration.ofSeconds(6))).retryPending();

        verify(client, times(2)).post(any(), any());
        assertThat(delivery().path("delivered").asBoolean()).isTrue();
        assertThat(delivery().path("attempts").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"200,ok", "503,success", "302,success"})
    void requiresBothSuccessfulHttpStatusAndSuccessAcknowledgement(int status, String body) throws Exception {
        prepare();
        MerchantCallbackTransport.Result unacknowledged = response(status, body);
        when(client.post(any(), any())).thenReturn(unacknowledged);

        service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");

        assertThat(delivery().path("delivered").asBoolean()).isFalse();
        assertThat(delivery().path("nextAttemptAt").asLong()).isEqualTo(initialTime.millis() + 5000);
    }

    @Test
    void retryResignsWithCurrentMerchantCredentialsAndKeepsOriginalNotificationTime() throws Exception {
        prepare();
        when(signatures.signForMerchant(any(), eq("MD5"), any())).thenReturn("old-signature", "new-signature");
        MerchantCallbackTransport.Result unavailable = response(500, "failed");
        MerchantCallbackTransport.Result acknowledged = response(200, "success");
        when(client.post(any(), any()))
                .thenReturn(unavailable, acknowledged);
        service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");
        String originalTime = delivery().path("payload").path("notifyTime").asText();

        service(Clock.offset(initialTime, Duration.ofSeconds(6))).retryPending();

        assertThat(delivery().path("payload").path("sign").asText()).isEqualTo("new-signature");
        assertThat(delivery().path("payload").path("notifyTime").asText()).isEqualTo(originalTime);
    }

    @Test
    void rejectsNonHttpCallbackBeforeSendingOrPersisting() throws Exception {
        prepare();
        when(orders.merchantNotifyTarget("ORDER")).thenReturn(Optional.of(new DemoOrderService.MerchantNotifyTarget("M1", "file:///secrets")));

        assertThatThrownBy(() -> service(initialTime).notifyPayment(order(), "TRADE_SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).post(any(), any());
        try (var files = Files.list(directory)) {
            assertThat(files.filter(path -> path.toString().endsWith(".json"))).isEmpty();
        }
    }

    @Test
    void unpaidOrderNeverCreatesSuccessNotification() throws Exception {
        DemoOrderView unpaid = order();
        when(unpaid.status()).thenReturn(DemoOrderStatus.UNPAID);

        service(initialTime).notifyPayment(unpaid, "WAIT_BUYER_PAY");

        verify(orders, never()).merchantNotifyTarget(any());
        verify(client, never()).post(any(), any());
    }

    private void prepare() {
        when(orders.merchantNotifyTarget("ORDER")).thenReturn(Optional.of(new DemoOrderService.MerchantNotifyTarget("M1", "https://merchant.example/notify")));
        DemoMerchantView merchant = mock(DemoMerchantView.class);
        when(merchants.detail("M1")).thenReturn(merchant);
        when(signatures.defaultSignType(merchant)).thenReturn("MD5");
        when(signatures.signForMerchant(any(), eq("MD5"), any())).thenReturn("signature");
    }

    private MerchantNotifyService service(Clock clock) {
        return new MerchantNotifyService(orders, merchants, signatures, json, directory, client, clock, Runnable::run);
    }

    private JsonNode delivery() throws IOException {
        try (var files = Files.list(directory)) {
            Path file = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
            return json.readTree(file.toFile());
        }
    }

    private static DemoOrderView order() {
        DemoOrderView order = mock(DemoOrderView.class);
        when(order.merchantId()).thenReturn("M1");
        when(order.outTradeNo()).thenReturn("ORDER");
        when(order.tradeNo()).thenReturn("TRADE");
        when(order.channelId()).thenReturn("ali");
        when(order.productName()).thenReturn("支付宝");
        when(order.amount()).thenReturn(new BigDecimal("1.00"));
        when(order.status()).thenReturn(DemoOrderStatus.COMPLETED);
        return order;
    }

    private static MerchantCallbackTransport.Result response(int code, String body) {
        return new MerchantCallbackTransport.Result(code, body);
    }
}
