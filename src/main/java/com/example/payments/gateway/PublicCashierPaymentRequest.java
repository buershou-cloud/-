package com.example.payments.gateway;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The anonymous cashier is not the merchant API: upstream signing options are server owned. */
public final class PublicCashierPaymentRequest {
    private static final Set<String> RESERVED_EXTRA = Set.of(
            "merchantapirequest", "outtradeno", "tradeno", "outorderno", "outrequestno", "outrefundno",
            "totalamount", "refundamount", "amount", "alipaymethod", "preauthmethod");

    private PublicCashierPaymentRequest() { }

    public static PayCreateRequest sanitize(PayCreateRequest request) {
        Map<String, Object> supplied = request.extra() == null ? Map.of() : request.extra();
        if (supplied.keySet().stream().anyMatch(key -> key != null
                && RESERVED_EXTRA.contains(key.replace("_", "").toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("extra must not override payment identifiers, amount, API method or merchant API authentication");
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("cashier", true);
        for (String key : Set.of("merchantId", "merchantName", "payer_client_ip", "user_agent")) {
            if (supplied.get(key) instanceof String text && !text.isBlank()) extra.put(key, text);
        }
        if ("ALIAPP".equals(supplied.get("integration_type"))) extra.put("integration_type", "ALIAPP");
        if ((request.product() == PaymentProduct.DOUYIN_H5 || request.product() == PaymentProduct.DOUYIN_NATIVE)
                && supplied.get("settle_info") instanceof Map<?, ?> settle
                && settle.get("profit_sharing") instanceof Boolean enabled) {
            extra.put("settle_info", Map.of("profit_sharing", enabled));
        }
        return copy(request, request.quitUrl(), null, request.returnUrl(), extra);
    }

    public static PayCreateRequest forChannel(PayCreateRequest request, PaymentGatewayProperties.Channel channel) {
        PayCreateRequest safe = sanitize(request);
        boolean douyin = "DOUYIN".equalsIgnoreCase(channel.getProvider());
        String callback = douyin ? channel.getDouyin().getNotifyUrl() : channel.getAlipay().getNotifyUrl();
        String configuredReturn = douyin ? channel.getDouyin().getReturnUrl() : channel.getAlipay().getReturnUrl();
        if (callback == null || callback.isBlank()) {
            throw new GatewayException("CASHIER_NOTIFY_URL_MISSING", "收银台支付通道必须配置网关支付回调地址");
        }
        String returnUrl = allowedReturn(safe.returnUrl(), callback, configuredReturn) ? safe.returnUrl() : configuredReturn;
        String quitUrl = allowedReturn(safe.quitUrl(), callback, configuredReturn) ? safe.quitUrl() : returnUrl;
        Map<String, Object> extra = new LinkedHashMap<>(safe.extra());
        if (extra.containsKey("integration_type") && returnUrl != null && !returnUrl.isBlank()) {
            extra.put("request_from_url", returnUrl);
        }
        return copy(safe, quitUrl, callback, returnUrl, extra);
    }

    private static boolean allowedReturn(String requested, String callback, String configuredReturn) {
        return sameOrigin(requested, callback) || sameOrigin(requested, configuredReturn);
    }

    private static boolean sameOrigin(String first, String second) {
        try {
            URI left = URI.create(first);
            URI right = URI.create(second);
            return ("https".equalsIgnoreCase(left.getScheme()) || "http".equalsIgnoreCase(left.getScheme()))
                    && left.getHost() != null && left.getUserInfo() == null && right.getHost() != null
                    && left.getScheme().equalsIgnoreCase(right.getScheme())
                    && left.getHost().equalsIgnoreCase(right.getHost()) && port(left) == port(right);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static PayCreateRequest copy(PayCreateRequest request, String quitUrl, String notifyUrl,
                                         String returnUrl, Map<String, Object> extra) {
        return new PayCreateRequest(request.product(), request.outTradeNo(), request.subject(), request.totalAmount(),
                request.authCode(), request.buyerId(), request.buyerOpenId(), quitUrl, request.timeoutExpress(),
                notifyUrl, returnUrl, null, request.routingMode(), request.channelIds(), extra, null, null);
    }
}
