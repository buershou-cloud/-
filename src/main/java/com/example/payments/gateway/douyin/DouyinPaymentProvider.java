package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRelationQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.PaymentProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DouyinPaymentProvider implements PaymentProvider {

    private static final String H5_ORDER_PATH = "/v1/trade/transactions/h5";
    private static final String QUERY_BY_OUT_TRADE_NO = "/v1/trade/transactions/out-trade-no/";
    private static final String QUERY_BY_TRANSACTION_ID = "/v1/trade/transactions/id/";
    private static final String REFUND_PATH = "/v1/trade/refund/domestic/refunds";

    private final DouyinPayClient client;

    public DouyinPaymentProvider(DouyinPayClient client) {
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "DOUYIN";
    }

    @Override
    public boolean supports(PaymentGatewayProperties.Channel channel, PaymentProduct product) {
        return "DOUYIN".equals(channel.getProvider())
                && product == PaymentProduct.DOUYIN_H5
                && (channel.getProducts().isEmpty() || channel.getProducts().contains(product));
    }

    @Override
    public GatewayResponse pay(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        if (request.product() != PaymentProduct.DOUYIN_H5) {
            throw new GatewayException("UNSUPPORTED_PRODUCT", "Douyin channel currently supports only Douyin H5 payment");
        }
        PaymentGatewayProperties.Douyin config = channel.getDouyin();
        String notifyUrl = firstText(config.getNotifyUrl(), request.notifyUrl());
        if (!hasText(notifyUrl)) {
            throw new GatewayException("DOUYIN_NOTIFY_URL_MISSING", "Douyin Pay notify URL is required");
        }

        Map<String, Object> h5Info = new LinkedHashMap<>();
        h5Info.put("type", "Wap");
        h5Info.put("app_name", firstText(config.getH5AppName(), "Payment Gateway"));
        putIfText(h5Info, "app_url", firstText(request.returnUrl(), config.getReturnUrl()));

        Map<String, Object> sceneInfo = new LinkedHashMap<>();
        sceneInfo.put("payer_client_ip", firstText(extraText(request.extra(), "payer_client_ip"), "127.0.0.1"));
        sceneInfo.put("h5_info", h5Info);
        putIfText(sceneInfo, "device_id", extraText(request.extra(), "device_id"));
        putIfText(sceneInfo, "user_agent", extraText(request.extra(), "user_agent"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", required(config.getAppId(), "Douyin Pay appId is required"));
        body.put("mchid", required(config.getMchId(), "Douyin Pay mchId is required"));
        body.put("description", request.subject());
        body.put("out_trade_no", request.outTradeNo());
        body.put("notify_url", notifyUrl);
        body.put("amount", amount(request.totalAmount()));
        body.put("scene_info", sceneInfo);
        mergeAllowedPayExtras(body, request.extra());

        DouyinGatewayResponse response = client.post(channel, H5_ORDER_PATH, body);
        String h5Url = firstText(text(response.body(), "h5_url"), nestedText(response.body(), "data", "h5_url"));
        if (!hasText(h5Url)) {
            throw new GatewayException("DOUYIN_H5_URL_MISSING", "Douyin Pay did not return h5_url");
        }
        String transactionId = firstText(
                text(response.body(), "transaction_id"),
                nestedText(response.body(), "data", "transaction_id")
        );
        return new GatewayResponse(
                channel.getId(),
                PaymentStatus.PENDING,
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "message"), "Douyin H5 order created"),
                request.outTradeNo(),
                transactionId,
                null,
                null,
                h5Url,
                responseRaw(response, H5_ORDER_PATH),
                List.of()
        );
    }

    @Override
    public GatewayResponse query(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request) {
        String path = queryPath(channel, request.outTradeNo(), request.tradeNo());
        DouyinGatewayResponse response = client.get(channel, path);
        return transactionResponse(channel.getId(), response, request.outTradeNo(), path);
    }

    @Override
    public GatewayResponse cancel(PaymentGatewayProperties.Channel channel, PaymentCancelRequest request) {
        String outTradeNo = request.outTradeNo();
        if (!hasText(outTradeNo)) {
            DouyinGatewayResponse query = client.get(channel, queryPath(channel, null, request.tradeNo()));
            outTradeNo = text(query.body(), "out_trade_no");
        }
        if (!hasText(outTradeNo)) {
            throw new GatewayException("DOUYIN_OUT_TRADE_NO_MISSING", "Douyin Pay close requires outTradeNo");
        }
        String path = QUERY_BY_OUT_TRADE_NO + path(outTradeNo) + "/close";
        DouyinGatewayResponse response = client.post(channel, path, Map.of("mchid", channel.getDouyin().getMchId()));
        return new GatewayResponse(
                channel.getId(),
                PaymentStatus.CLOSED,
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "message"), "Douyin Pay order closed"),
                outTradeNo,
                request.tradeNo(),
                null,
                null,
                responseRaw(response, path),
                List.of()
        );
    }

    @Override
    public GatewayResponse refund(PaymentGatewayProperties.Channel channel, RefundCreateRequest request) {
        long originalTotal = longExtra(request.extra(), "douyin_total_amount_fen");
        if (originalTotal <= 0) {
            throw new GatewayException("DOUYIN_ORIGINAL_AMOUNT_MISSING", "Original Douyin Pay order amount is required for refund");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", channel.getDouyin().getMchId());
        putIfText(body, "transaction_id", request.tradeNo());
        putIfText(body, "out_trade_no", request.outTradeNo());
        body.put("out_refund_no", request.outRequestNo());
        putIfText(body, "reason", request.refundReason());
        body.put("notify_url", firstText(extraText(request.extra(), "notify_url"), channel.getDouyin().getNotifyUrl()));
        body.put("amount", Map.of(
                "refund", fen(request.refundAmount()),
                "total", originalTotal,
                "currency", "CNY"
        ));

        DouyinGatewayResponse response = client.post(channel, REFUND_PATH, body);
        String refundStatus = firstText(text(response.body(), "status"), text(response.body(), "refund_status"));
        String transactionId = firstText(text(response.body(), "transaction_id"), request.tradeNo());
        return new GatewayResponse(
                channel.getId(),
                refundStatus(refundStatus),
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "message"), "Douyin Pay refund accepted"),
                firstText(text(response.body(), "out_trade_no"), request.outTradeNo()),
                transactionId,
                null,
                null,
                responseRaw(response, REFUND_PATH),
                List.of()
        );
    }

    @Override
    public GatewayResponse preauthQuery(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request) {
        throw unsupported("pre-authorization query");
    }

    @Override
    public GatewayResponse preauthCapture(PaymentGatewayProperties.Channel channel, PreauthCaptureRequest request) {
        throw unsupported("pre-authorization capture");
    }

    @Override
    public GatewayResponse preauthUnfreeze(PaymentGatewayProperties.Channel channel, PreauthUnfreezeRequest request) {
        throw unsupported("pre-authorization unfreeze");
    }

    @Override
    public GatewayResponse profitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request) {
        throw unsupported("profit sharing");
    }

    @Override
    public GatewayResponse bindProfitSharingRelation(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingRelationBindRequest request
    ) {
        throw unsupported("profit sharing relation binding");
    }

    @Override
    public GatewayResponse queryProfitSharingRelations(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingRelationQueryRequest request
    ) {
        throw unsupported("profit sharing relation query");
    }

    @Override
    public GatewayResponse queryComplaints(PaymentGatewayProperties.Channel channel, ComplaintQueryRequest request) {
        throw unsupported("complaint query");
    }

    @Override
    public GatewayResponse onboard(PaymentGatewayProperties.Channel channel, OnboardingRequest request) {
        throw unsupported("onboarding");
    }

    private String queryPath(
            PaymentGatewayProperties.Channel channel,
            String outTradeNo,
            String transactionId
    ) {
        String base;
        if (hasText(outTradeNo)) {
            base = QUERY_BY_OUT_TRADE_NO + path(outTradeNo);
        } else if (hasText(transactionId)) {
            base = QUERY_BY_TRANSACTION_ID + path(transactionId);
        } else {
            throw new GatewayException("DOUYIN_ORDER_ID_MISSING", "outTradeNo or transactionId is required");
        }
        return base + "?mchid=" + query(channel.getDouyin().getMchId());
    }

    private GatewayResponse transactionResponse(
            String channelId,
            DouyinGatewayResponse response,
            String fallbackOutTradeNo,
            String path
    ) {
        return new GatewayResponse(
                channelId,
                tradeStatus(text(response.body(), "trade_state")),
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "trade_state_desc"), text(response.body(), "message")),
                firstText(text(response.body(), "out_trade_no"), fallbackOutTradeNo),
                text(response.body(), "transaction_id"),
                null,
                null,
                responseRaw(response, path),
                List.of()
        );
    }

    private static PaymentStatus tradeStatus(String value) {
        if ("SUCCESS".equals(value)) {
            return PaymentStatus.SUCCESS;
        }
        if ("CLOSED".equals(value)) {
            return PaymentStatus.CLOSED;
        }
        if ("NOTPAY".equals(value) || "USERPAYING".equals(value)) {
            return PaymentStatus.PAYING;
        }
        return PaymentStatus.UNKNOWN;
    }

    private static PaymentStatus refundStatus(String value) {
        if ("SUCCESS".equals(value)) {
            return PaymentStatus.SUCCESS;
        }
        if ("CLOSED".equals(value) || "ABNORMAL".equals(value)) {
            return PaymentStatus.FAILED;
        }
        return PaymentStatus.PENDING;
    }

    private static Map<String, Object> amount(BigDecimal total) {
        return Map.of("total", fen(total), "currency", "CNY");
    }

    private static long fen(BigDecimal amount) {
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException ex) {
            throw new GatewayException("DOUYIN_AMOUNT_INVALID", "Douyin Pay amount must have no more than two decimals", ex);
        }
    }

    private static void mergeAllowedPayExtras(Map<String, Object> body, Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        copy(extra, body, "goods_tag");
        copy(extra, body, "attach");
        copy(extra, body, "limit_pay");
        copy(extra, body, "settle_info");
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> responseRaw(DouyinGatewayResponse response, String path) {
        Map<String, Object> raw = new LinkedHashMap<>(response.body());
        raw.put("request_path", path);
        raw.put("http_status", response.httpStatus());
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static String nestedText(Map<String, Object> data, String objectKey, String valueKey) {
        Object nested = data == null ? null : data.get(objectKey);
        if (nested instanceof Map<?, ?> map) {
            return text((Map<String, Object>) map, valueKey);
        }
        return null;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : value.toString();
    }

    private static String extraText(Map<String, Object> extra, String key) {
        Object value = extra == null ? null : extra.get(key);
        return value == null ? null : value.toString();
    }

    private static long longExtra(Map<String, Object> extra, String key) {
        Object value = extra == null ? null : extra.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void putIfText(Map<String, Object> data, String key, String value) {
        if (hasText(value)) {
            data.put(key, value.trim());
        }
    }

    private static String required(String value, String message) {
        if (!hasText(value)) {
            throw new GatewayException("DOUYIN_CONFIG_MISSING", message);
        }
        return value.trim();
    }

    private static GatewayException unsupported(String operation) {
        return new GatewayException("DOUYIN_UNSUPPORTED_OPERATION", "Douyin H5 does not support " + operation + " in this gateway");
    }

    private static String path(String value) {
        return UriUtils.encodePathSegment(value.trim(), StandardCharsets.UTF_8);
    }

    private static String query(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
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
