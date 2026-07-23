package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class DouyinPayClient {

    private static final Logger log = LoggerFactory.getLogger(DouyinPayClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DouyinPayClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public DouyinGatewayResponse post(
            PaymentGatewayProperties.Channel channel,
            String path,
            Map<String, Object> body
    ) {
        return post(channel, path, body, false);
    }

    public DouyinGatewayResponse postSensitive(
            PaymentGatewayProperties.Channel channel,
            String path,
            Map<String, Object> body
    ) {
        return post(channel, path, body, true);
    }

    private DouyinGatewayResponse post(
            PaymentGatewayProperties.Channel channel,
            String path,
            Map<String, Object> body,
            boolean sensitive
    ) {
        try {
            return execute(
                    channel,
                    "POST",
                    path,
                    objectMapper.writeValueAsString(body == null ? Map.of() : body),
                    sensitive
            );
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_SERIALIZE_ERROR", "Failed to serialize Douyin Pay request", ex);
        }
    }

    public DouyinGatewayResponse get(PaymentGatewayProperties.Channel channel, String pathAndQuery) {
        return execute(channel, "GET", pathAndQuery, "", false);
    }

    public boolean verifyNotification(
            PaymentGatewayProperties.Channel channel,
            String timestamp,
            String nonce,
            String signature,
            String serial,
            String rawBody
    ) {
        if (!hasText(timestamp) || !hasText(nonce) || !hasText(signature) || !hasText(serial)) {
            return false;
        }
        String expectedSerial = DouyinSignatureSupport.certificateSerial(channel.getDouyin().getPlatformCertificate());
        if (!expectedSerial.equalsIgnoreCase(serial.trim())) {
            return false;
        }
        return DouyinSignatureSupport.verify(
                responseMessage(timestamp, nonce, rawBody),
                signature,
                channel.getDouyin().getPlatformCertificate()
        );
    }

    private DouyinGatewayResponse execute(
            PaymentGatewayProperties.Channel channel,
            String method,
            String pathAndQuery,
            String body,
            boolean sensitive
    ) {
        try {
            PaymentGatewayProperties.Douyin config = channel.getDouyin();
            String normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
            URI uri = URI.create(trimSlash(config.getGatewayUrl()) + normalizedPath);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = DouyinSignatureSupport.sign(
                    requestMessage(method, normalizedPath, timestamp, nonce, body),
                    config.getMerchantPrivateKey()
            );
            String authorization = "DouyinPay-RSA mchid=\"" + config.getMchId()
                    + "\",nonce_str=\"" + nonce
                    + "\",timestamp=\"" + timestamp
                    + "\",serial_no=\"" + config.getMerchantSerialNo()
                    + "\",signature=\"" + signature + "\"";

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Authorization", authorization);
            if (sensitive) {
                builder.header(
                        "Douyinpay-Serial",
                        DouyinSignatureSupport.certificateSerial(config.getPlatformCertificate())
                );
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            log.info("Sending Douyin Pay request method={} path={} channel={}", method, normalizedPath, channel.getId());
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Map<String, String> headers = responseHeaders(response);
            verifyResponseIfPresent(channel, headers, response.body());
            Map<String, Object> parsed = parseBody(response.body());
            log.info(
                    "Received Douyin Pay response method={} path={} status={} logId={} channel={}",
                    method,
                    normalizedPath,
                    response.statusCode(),
                    firstText(extractResponseLogId(headers, parsed), "-"),
                    channel.getId()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GatewayException(
                        firstText(text(parsed, "code"), "DOUYIN_HTTP_" + response.statusCode()),
                        gatewayErrorMessage(parsed, response.body())
                );
            }
            return new DouyinGatewayResponse(response.statusCode(), parsed, response.body(), headers);
        } catch (GatewayException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GatewayException("DOUYIN_REQUEST_INTERRUPTED", "Douyin Pay request was interrupted", ex);
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_REQUEST_ERROR", "Failed to call Douyin Pay gateway", ex);
        }
    }

    private void verifyResponseIfPresent(
            PaymentGatewayProperties.Channel channel,
            Map<String, String> headers,
            String rawBody
    ) {
        String timestamp = headers.get("douyinpay-timestamp");
        String nonce = headers.get("douyinpay-nonce");
        String signature = headers.get("douyinpay-signature");
        String serial = headers.get("douyinpay-serial");
        if (!hasText(timestamp) && !hasText(nonce) && !hasText(signature) && !hasText(serial)) {
            return;
        }
        if (!verifyNotification(channel, timestamp, nonce, signature, serial, rawBody)) {
            throw new GatewayException("DOUYIN_RESPONSE_SIGNATURE_INVALID", "Invalid Douyin Pay response signature");
        }
    }

    private Map<String, Object> parseBody(String rawBody) {
        if (!hasText(rawBody)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawBody, MAP_TYPE);
        } catch (Exception ex) {
            throw new GatewayException("DOUYIN_RESPONSE_INVALID", "Douyin Pay returned invalid JSON", ex);
        }
    }

    static String gatewayErrorMessage(Map<String, Object> parsed, String rawBody) {
        String message = firstText(text(parsed, "message"), text(parsed, "msg"), rawBody, "Douyin Pay request failed");
        Object detail = parsed == null ? null : parsed.get("detail");
        if (!(detail instanceof Map<?, ?> detailMap) || detailMap.isEmpty()) {
            return message;
        }

        String field = objectText(detailMap.get("field"));
        String issue = objectText(detailMap.get("issue"));
        String location = objectText(detailMap.get("location"));
        StringBuilder result = new StringBuilder(message);
        if (hasText(field) || hasText(issue) || hasText(location)) {
            result.append("（");
            boolean appended = false;
            if (hasText(field)) {
                result.append("字段：").append(field);
                appended = true;
            }
            if (hasText(issue)) {
                if (appended) {
                    result.append("；");
                }
                result.append("原因：").append(issue);
                appended = true;
            }
            if (hasText(location)) {
                if (appended) {
                    result.append("；");
                }
                result.append("位置：").append(location);
            }
            result.append("）");
        }
        return result.toString();
    }

    static String requestMessage(String method, String pathAndQuery, String timestamp, String nonce, String body) {
        return method + "\n" + pathAndQuery + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
    }

    static String responseMessage(String timestamp, String nonce, String body) {
        return timestamp + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
    }

    private static Map<String, String> responseHeaders(HttpResponse<?> response) {
        Map<String, String> result = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                result.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        return result;
    }

    static String extractResponseLogId(Map<String, String> headers, Map<String, Object> body) {
        return firstText(
                headerText(headers, "x-tt-logid"),
                headerText(headers, "x-tt-log-id"),
                headerText(headers, "logid"),
                headerText(headers, "log-id"),
                headerText(headers, "x-log-id"),
                text(body, "log_id"),
                text(body, "logId"),
                text(body, "logid")
        );
    }

    private static String headerText(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty() || !hasText(name)) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : value.toString();
    }

    private static String objectText(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
