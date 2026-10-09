package com.example.payments.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TelegramBotClientTest {
    private static final String TOKEN = "12345678:abcdefghijklmnopqrstuvwxyz_123456789";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = mock(HttpClient.class);

    @Test
    void onlyFixedHttpsEndpointReceivesPlainUnpaidMessageAndConfirmedMessageId() throws Exception {
        stub(200, "{\"ok\":true,\"result\":{\"message_id\":123}}");
        TelegramTransport.Result result = new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "收款成功 <b>原文</b>");
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo(123);
        var capture = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(capture.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = capture.getValue();
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo("api.telegram.org");
        assertThat(request.uri().getPath()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.timeout()).contains(Duration.ofSeconds(5));
        var body = new TelegramBotClient.LimitedBodySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
            @Override public void onNext(ByteBuffer item) { body.onNext(List.of(item)); }
            @Override public void onError(Throwable throwable) { body.onError(throwable); }
            @Override public void onComplete() { body.onComplete(); }
        });
        var json = mapper.readTree(body.getBody().toCompletableFuture().join());
        assertThat(json.path("chat_id").asText()).isEqualTo("-100123");
        assertThat(json.path("text").asText()).isEqualTo("收款成功 <b>原文</b>");
        assertThat(json.path("allow_paid_broadcast").booleanValue()).isFalse();
        assertThat(json.has("parse_mode")).isFalse();
        assertThat(json.toString()).doesNotContain(TOKEN);
    }

    @Test
    void failedAndMalformedResponsesNeverCountAsSuccessfulDelivery() {
        String[] bodies = {"{\"ok\":false}", "{\"ok\":true}", "{\"ok\":true,\"result\":{\"message_id\":0}}",
                "{\"ok\":\"true\",\"result\":{\"message_id\":1}}", "not-json"};
        for (String body : bodies) {
            stub(200, body);
            assertThat(new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试").success()).isFalse();
        }
    }

    @Test
    void rateLimitUsesRetryAfterWithoutLeakingUpstreamDescription() {
        stub(429, "{\"ok\":false,\"error_code\":429,\"description\":\"" + TOKEN + "\",\"parameters\":{\"retry_after\":35}}");
        TelegramTransport.Result result = new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试");
        assertThat(result.code()).isEqualTo("TELEGRAM_RATE_LIMITED");
        assertThat(result.retryAfterSeconds()).isEqualTo(35);
        assertThat(result.message()).doesNotContain(TOKEN);
    }

    @Test
    void credentialsAndPermissionErrorsUseSanitizedDiagnostics() {
        stub(401, "{\"ok\":false,\"error_code\":401,\"description\":\"" + TOKEN + "\"}");
        TelegramTransport.Result rejected = new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试");
        assertThat(rejected.code()).isEqualTo("TELEGRAM_TOKEN_REJECTED");
        assertThat(rejected.message()).doesNotContain(TOKEN);
        stub(403, "{\"ok\":false,\"error_code\":403}");
        assertThat(new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试").code()).isEqualTo("TELEGRAM_CHAT_REJECTED");
        stub(302, "{}");
        assertThat(new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试").code()).isEqualTo("TELEGRAM_REDIRECT_REJECTED");
    }

    @Test
    void transportExceptionCannotExposeTokenBearingUrl() {
        CompletableFuture<HttpResponse<byte[]>> failed = CompletableFuture.failedFuture(new IllegalStateException("https://api.telegram.org/bot" + TOKEN));
        doReturn(failed).when(http).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        TelegramTransport.Result result = new TelegramBotClient(mapper, http).send(TOKEN, "-100123", "测试");
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("TELEGRAM_NETWORK_ERROR");
        assertThat(result.message()).doesNotContain(TOKEN, "https");
    }

    @Test
    void responseSubscriberCancelsOversizedBodyBeforeUnboundedAllocation() {
        var subscriber = new TelegramBotClient.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[64 * 1024])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasCauseInstanceOf(java.io.IOException.class);
    }

    @SuppressWarnings("unchecked")
    private void stub(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        doReturn(status).when(response).statusCode();
        doReturn(body.getBytes(StandardCharsets.UTF_8)).when(response).body();
        doReturn(CompletableFuture.completedFuture(response)).when(http).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
