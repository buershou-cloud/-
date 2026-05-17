package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class AlipayOpenApiClient {

    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AlipayOpenApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public AlipayGatewayResponse execute(
            PaymentGatewayProperties.Channel channel,
            String method,
            Map<String, Object> bizContent,
            AlipayRequestOptions options
    ) {
        try {
            Map<String, String> params = signedParams(channel, method, bizContent, options);
            Charset charset = charset(channel);
            String body = formEncode(params, charset);
            HttpRequest request = HttpRequest.newBuilder(URI.create(channel.getAlipay().getGatewayUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=" + charset.name())
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, charset))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(charset));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new GatewayException("ALIPAY_HTTP_" + response.statusCode(), response.body());
            }
            return parse(method, response.body());
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_REQUEST_ERROR", "Failed to call Alipay gateway", ex);
        }
    }

    public String pageForm(
            PaymentGatewayProperties.Channel channel,
            String method,
            Map<String, Object> bizContent,
            AlipayRequestOptions options
    ) {
        Map<String, String> params = signedParams(channel, method, bizContent, options);
        Charset charset = charset(channel);
        String gatewayUrl = gatewayWithCharset(channel.getAlipay().getGatewayUrl(), charset);
        StringBuilder form = new StringBuilder(2048);
        form.append("<!doctype html><html><head><meta charset=\"UTF-8\"></head><body>")
                .append("<form id=\"alipay_submit\" name=\"alipay_submit\" action=\"")
                .append(HtmlUtils.htmlEscape(gatewayUrl))
                .append("\" method=\"POST\">");
        params.forEach((key, value) -> form.append("<input type=\"hidden\" name=\"")
                .append(HtmlUtils.htmlEscape(key))
                .append("\" value=\"")
                .append(HtmlUtils.htmlEscape(value))
                .append("\"/>"));
        form.append("</form><script>document.forms['alipay_submit'].submit();</script></body></html>");
        return form.toString();
    }

    public String pageUrl(
            PaymentGatewayProperties.Channel channel,
            String method,
            Map<String, Object> bizContent,
            AlipayRequestOptions options
    ) {
        Map<String, String> params = signedParams(channel, method, bizContent, options);
        Charset charset = charset(channel);
        return appendQuery(channel.getAlipay().getGatewayUrl(), formEncode(params, charset));
    }

    public boolean verifyNotify(PaymentGatewayProperties.Channel channel, Map<String, String> params) {
        Charset charset = charset(channel);
        return AlipaySignatureSupport.verify(
                params,
                AlipayCertificateSupport.alipayPublicKey(channel.getAlipay()),
                Optional.ofNullable(params.get("sign_type")).orElse(channel.getAlipay().getSignType()),
                charset
        );
    }

    private Map<String, String> signedParams(
            PaymentGatewayProperties.Channel channel,
            String method,
            Map<String, Object> bizContent,
            AlipayRequestOptions options
    ) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        validateCredentials(channel);
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("app_id", alipay.getAppId());
            params.put("method", method);
            params.put("format", "JSON");
            params.put("charset", alipay.getCharset());
            params.put("sign_type", alipay.getSignType());
            params.put("timestamp", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(ALIPAY_TIME));
            params.put("version", "1.0");
            params.put("biz_content", objectMapper.writeValueAsString(bizContent == null ? Map.of() : bizContent));
            putIfPresent(params, "notify_url", firstText(options == null ? null : options.notifyUrl(), alipay.getNotifyUrl()));
            putIfPresent(params, "return_url", firstText(options == null ? null : options.returnUrl(), alipay.getReturnUrl()));
            putIfPresent(params, "app_auth_token", firstText(options == null ? null : options.appAuthToken(), alipay.getAppAuthToken()));
            if (AlipayCertificateSupport.certificateMode(alipay)) {
                putIfPresent(params, "app_cert_sn", AlipayCertificateSupport.appCertSn(alipay));
                putIfPresent(params, "alipay_root_cert_sn", AlipayCertificateSupport.alipayRootCertSn(alipay));
            }
            params.put("sign", AlipaySignatureSupport.sign(params, alipay.getMerchantPrivateKey(), alipay.getSignType(), charset(channel)));
            return params;
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_SERIALIZE_ERROR", "Failed to serialize Alipay request", ex);
        }
    }

    private AlipayGatewayResponse parse(String method, String body) {
        try {
            Map<String, Object> raw = objectMapper.readValue(body, MAP_TYPE);
            String responseKey = raw.keySet().stream()
                    .filter(key -> key.endsWith("_response"))
                    .findFirst()
                    .orElse(null);
            Map<String, Object> response = responseKey == null ? Map.of() : asMap(raw.get(responseKey));
            String code = asString(response.get("code"));
            String message = firstText(asString(response.get("msg")), asString(response.get("sub_msg")));
            String subCode = asString(response.get("sub_code"));
            String subMessage = asString(response.get("sub_msg"));
            boolean success = "10000".equals(code);
            return new AlipayGatewayResponse(method, responseKey, success, code, message, subCode, subMessage, response, raw);
        } catch (Exception ex) {
            throw new GatewayException("ALIPAY_PARSE_ERROR", "Failed to parse Alipay response: " + body, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String formEncode(Map<String, String> params, Charset charset) {
        return params.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey(), charset) + "=" + urlEncode(entry.getValue(), charset))
                .collect(Collectors.joining("&"));
    }

    private static String gatewayWithCharset(String gatewayUrl, Charset charset) {
        return appendQuery(gatewayUrl, "charset=" + urlEncode(charset.name(), charset));
    }

    private static String appendQuery(String url, String query) {
        if (url == null || url.isBlank() || query == null || query.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private static String urlEncode(String value, Charset charset) {
        return URLEncoder.encode(value == null ? "" : value, charset);
    }

    private static Charset charset(PaymentGatewayProperties.Channel channel) {
        return Charset.forName(firstText(channel.getAlipay().getCharset(), "UTF-8"));
    }

    private static void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private static String firstText(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void validateCredentials(PaymentGatewayProperties.Channel channel) {
        PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
        if (isBlank(alipay.getGatewayUrl()) || isBlank(alipay.getAppId()) || isBlank(alipay.getMerchantPrivateKey())) {
            throw new GatewayException(
                    "ALIPAY_CONFIG_MISSING",
                    "Alipay channel " + channel.getId() + " requires gatewayUrl, appId and merchantPrivateKey"
            );
        }
        if (AlipayCertificateSupport.certificateMode(alipay)
                && (isBlank(AlipayCertificateSupport.appCertSn(alipay))
                || isBlank(AlipayCertificateSupport.alipayRootCertSn(alipay)))) {
            throw new GatewayException(
                    "ALIPAY_CERTIFICATE_CONFIG_MISSING",
                    "Alipay certificate mode requires appCertSn and alipayRootCertSn"
            );
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
