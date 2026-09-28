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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

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
    private final HttpClient client = mock(HttpClient.class);

    @Test
    void notifiesWithoutJdbcAndDeduplicatesAcknowledgedStateAcrossRestart() throws Exception {
        prepare();
        HttpResponse<String> acknowledged = response(200, " success\n");
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(acknowledged);
        MerchantNotifyService service = service(initialTime);

        service.notifyPayment(order(), "TRADE_SUCCESS");
        service.notifyPayment(order(), "TRADE_FINISHED");
        service(Clock.offset(initialTime, Duration.ofDays(1))).notifyPayment(order(), "TRADE_SUCCESS");

        verify(client, times(1)).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        JsonNode persisted = delivery();
        assertThat(persisted.path("delivered").asBoolean()).isTrue();
        assertThat(persisted.path("payload").path("totalAmount").asText()).isEqualTo("1.00");
        assertThat(persisted.path("payload").path("sign").asText()).isEqualTo("signature");
        assertThat(persisted.toString()).doesNotContain("md5Key", "privateKey");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString()).isEqualTo("https://merchant.example/notify");
    }

    @Test
    void failedDeliveryIsAlreadyOnDiskThenRetriesAfterRestartWithoutOrderLookup() throws Exception {
        prepare();
        HttpResponse<String> acknowledged = response(200, "success");
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    assertThat(delivery().path("attempts").asInt()).isZero();
                    throw new IOException("offline");
                }).thenReturn(acknowledged);
        service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");
        assertThat(delivery().path("delivered").asBoolean()).isFalse();
        assertThat(delivery().path("attempts").asInt()).isEqualTo(1);

        when(orders.merchantNotifyTarget("ORDER")).thenReturn(Optional.empty());
        service(Clock.offset(initialTime, Duration.ofSeconds(4))).retryPending();
        verify(client, times(1)).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        service(Clock.offset(initialTime, Duration.ofSeconds(6))).retryPending();

        verify(client, times(2)).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(delivery().path("delivered").asBoolean()).isTrue();
        assertThat(delivery().path("attempts").asInt()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"200,ok", "503,success", "302,success"})
    void requiresBothSuccessfulHttpStatusAndSuccessAcknowledgement(int status, String body) throws Exception {
        prepare();
        HttpResponse<String> unacknowledged = response(status, body);
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(unacknowledged);

        service(initialTime).notifyPayment(order(), "TRADE_SUCCESS");

        assertThat(delivery().path("delivered").asBoolean()).isFalse();
        assertThat(delivery().path("nextAttemptAt").asLong()).isEqualTo(initialTime.millis() + 5000);
    }

    @Test
    void retryResignsWithCurrentMerchantCredentialsAndKeepsOriginalNotificationTime() throws Exception {
        prepare();
        when(signatures.signForMerchant(any(), eq("MD5"), any())).thenReturn("old-signature", "new-signature");
        HttpResponse<String> unavailable = response(500, "failed");
        HttpResponse<String> acknowledged = response(200, "success");
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
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
        verify(client, never()).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
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
        verify(client, never()).send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private void prepare() {
        when(orders.merchantNotifyTarget("ORDER")).thenReturn(Optional.of(new DemoOrderService.MerchantNotifyTarget("M1", "https://merchant.example/notify")));
        DemoMerchantView merchant = mock(DemoMerchantView.class);
        when(merchants.detail("M1")).thenReturn(merchant);
        when(signatures.defaultSignType(merchant)).thenReturn("MD5");
        when(signatures.signForMerchant(any(), eq("MD5"), any())).thenReturn("signature");
    }

    private MerchantNotifyService service(Clock clock) {
        return new MerchantNotifyService(orders, merchants, signatures, json, directory, client, clock);
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

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int code, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body);
        return response;
    }
}
