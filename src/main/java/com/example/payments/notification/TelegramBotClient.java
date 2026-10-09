package com.example.payments.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed Telegram endpoint; response diagnostics deliberately exclude upstream text and credentials. */
final class TelegramBotClient implements TelegramTransport {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final ObjectMapper mapper;
    private final HttpClient client;

    TelegramBotClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    TelegramBotClient(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public Result send(String botToken, String chatId, String text) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            TelegramNotificationService.validateToken(botToken);
            TelegramNotificationService.validateChatId(chatId, true);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "chat_id", chatId, "text", text, "allow_paid_broadcast", false,
                            "link_preview_options", Map.of("is_disabled", true))), StandardCharsets.UTF_8)).build();
            exchange = client.sendAsync(request, ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = exchange.get(5, TimeUnit.SECONDS);
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return Result.failure("TELEGRAM_RESPONSE_TOO_LARGE", "Telegram 响应过大，稍后重试", 0);
            }
            JsonNode data;
            try {
                data = mapper.readTree(bytes);
            } catch (Exception ex) {
                return Result.failure("TELEGRAM_INVALID_RESPONSE", "Telegram 返回内容无法确认，稍后重试", 0);
            }
            if (data == null || !data.isObject()) {
                return Result.failure("TELEGRAM_INVALID_RESPONSE", "Telegram 返回内容无法确认，稍后重试", 0);
            }
            int errorCode = data.path("error_code").asInt(response.statusCode());
            if (response.statusCode() == 429 || errorCode == 429) {
                JsonNode retry = data.path("parameters").path("retry_after");
                long seconds = retry.isIntegralNumber() && retry.canConvertToLong() ? retry.longValue() : 0;
                return Result.failure("TELEGRAM_RATE_LIMITED", "Telegram 请求过于频繁，已暂停等待", Math.max(3, Math.min(seconds, Integer.MAX_VALUE)));
            }
            JsonNode messageId = data.path("result").path("message_id");
            if (response.statusCode() >= 200 && response.statusCode() < 300
                    && data.path("ok").isBoolean() && data.path("ok").booleanValue()
                    && messageId.isIntegralNumber() && messageId.canConvertToLong() && messageId.longValue() > 0) {
                return new Result(true, messageId.longValue(), "TELEGRAM_SENT", "测试消息已发送", 0);
            }
            if (errorCode == 401) {
                return Result.failure("TELEGRAM_TOKEN_REJECTED", "Telegram 拒绝机器人凭证，请检查 Token", 0);
            }
            if (errorCode == 400 || errorCode == 403) {
                return Result.failure("TELEGRAM_CHAT_REJECTED", "Telegram 拒绝发送，请检查群 ID 和机器人群权限", 0);
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return Result.failure("TELEGRAM_REDIRECT_REJECTED", "Telegram 返回了不支持的跳转", 0);
            }
            return Result.failure("TELEGRAM_UNCONFIRMED", "Telegram 未确认发送结果，稍后重试", 0);
        } catch (TimeoutException ex) {
            return Result.failure("TELEGRAM_TIMEOUT", "Telegram 发送超时，稍后重试", 0);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.failure("TELEGRAM_INTERRUPTED", "发送被中断，稍后重试", 0);
        } catch (Exception ex) {
            return Result.failure("TELEGRAM_NETWORK_ERROR", "无法连接 Telegram，请检查服务器网络或机器人配置", 0);
        } finally {
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
        }
    }

    /** The send future completes only after this bounded body, so the request timeout also covers body delivery. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Telegram response too large"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
