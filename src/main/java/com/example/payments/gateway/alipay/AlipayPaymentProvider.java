package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.PaymentProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AlipayPaymentProvider implements PaymentProvider {

    private static final String METHOD_WAP_PAY = "alipay.trade.wap.pay";
    private static final String METHOD_PAGE_PAY = "alipay.trade.page.pay";
    private static final String METHOD_TRADE_PAY = "alipay.trade.pay";
    private static final String METHOD_PRECREATE = "alipay.trade.precreate";
    private static final String METHOD_TRADE_CREATE = "alipay.trade.create";
    private static final String METHOD_TRADE_QUERY = "alipay.trade.query";
    private static final String METHOD_TRADE_REFUND = "alipay.trade.refund";
    private static final String METHOD_ORDER_SETTLE = "alipay.trade.order.settle";
    private static final String METHOD_PREAUTH_FREEZE = "alipay.fund.auth.order.app.freeze";
    private static final String METHOD_DIRECT_ZFT_SIMPLE_CREATE = "ant.merchant.expand.indirect.zft.simplecreate";

    private final PaymentGatewayProperties properties;
    private final AlipayOpenApiClient client;

    public AlipayPaymentProvider(PaymentGatewayProperties properties, AlipayOpenApiClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String providerCode() {
        return "ALIPAY";
    }

    @Override
    public boolean supports(PaymentGatewayProperties.Channel channel, PaymentProduct product) {
        return ("ALIPAY".equals(channel.getProvider()) || "ALIPAY_DIRECT".equals(channel.getProvider()))
                && (channel.getProducts().isEmpty() || channel.getProducts().contains(product));
    }

    @Override
    public GatewayResponse pay(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        return switch (request.product()) {
            case ALIPAY_WAP -> pagePay(channel, request, METHOD_WAP_PAY, "QUICK_WAP_WAY");
            case ALIPAY_PAGE -> pagePay(channel, request, METHOD_PAGE_PAY, "FAST_INSTANT_TRADE_PAY");
            case ALIPAY_F2F -> faceToFacePay(channel, request);
            case ALIPAY_ORDER_CODE -> precreate(channel, request);
            case ALIPAY_JSAPI -> jsapi(channel, request);
            case ALIPAY_PREAUTH -> preauth(channel, request);
            case ALIPAY_DIRECT -> directPay(channel, request);
            case ALIPAY_DIRECT_WAP -> pagePay(channel, request, METHOD_WAP_PAY, "QUICK_WAP_WAY");
            case ALIPAY_DIRECT_F2F -> faceToFacePay(channel, request);
            case ALIPAY_DIRECT_PAGE -> pagePay(channel, request, METHOD_PAGE_PAY, "FAST_INSTANT_TRADE_PAY");
            case ALIPAY_DIRECT_ORDER_CODE -> precreate(channel, request);
            case ALIPAY_DIRECT_JSAPI -> jsapi(channel, request);
        };
    }

    @Override
    public GatewayResponse query(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        putIfText(bizContent, "out_trade_no", request.outTradeNo());
        putIfText(bizContent, "trade_no", request.tradeNo());
        merge(bizContent, request.extra());
        AlipayGatewayResponse response = client.execute(channel, METHOD_TRADE_QUERY, bizContent, options(request.appAuthToken(), null, null));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    @Override
    public GatewayResponse refund(PaymentGatewayProperties.Channel channel, RefundCreateRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        putIfText(bizContent, "out_trade_no", request.outTradeNo());
        putIfText(bizContent, "trade_no", request.tradeNo());
        bizContent.put("refund_amount", amount(request.refundAmount()));
        bizContent.put("out_request_no", request.outRequestNo());
        putIfText(bizContent, "refund_reason", request.refundReason());
        merge(bizContent, request.extra());
        AlipayGatewayResponse response = client.execute(channel, METHOD_TRADE_REFUND, bizContent, options(request.appAuthToken(), null, null));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    @Override
    public GatewayResponse profitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        putIfText(bizContent, "out_trade_no", request.outTradeNo());
        putIfText(bizContent, "trade_no", request.tradeNo());
        bizContent.put("out_request_no", request.outRequestNo());
        bizContent.put("royalty_parameters", request.royaltyParameters());
        putIfText(bizContent, "operator_id", request.operatorId());
        merge(bizContent, request.extra());
        AlipayGatewayResponse response = client.execute(channel, METHOD_ORDER_SETTLE, bizContent, options(request.appAuthToken(), null, null));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    @Override
    public GatewayResponse queryComplaints(PaymentGatewayProperties.Channel channel, ComplaintQueryRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        String method;
        if (hasText(request.method())) {
            method = request.method();
        } else if (hasText(request.complaintId())) {
            method = properties.getOperations().getComplaintDetailMethod();
            bizContent.put("complaint_id", request.complaintId());
        } else {
            method = properties.getOperations().getComplaintListMethod();
            putIfText(bizContent, "begin_time", request.beginTime());
            putIfText(bizContent, "end_time", request.endTime());
            putIfPresent(bizContent, "page_num", request.pageNum());
            putIfPresent(bizContent, "page_size", request.pageSize());
        }
        merge(bizContent, request.extra());
        AlipayGatewayResponse response = client.execute(channel, method, bizContent, options(request.appAuthToken(), null, null));
        return apiResponse(channel.getId(), response, null, null);
    }

    @Override
    public GatewayResponse onboard(PaymentGatewayProperties.Channel channel, OnboardingRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>(request.payload());
        String method = hasText(request.method()) ? request.method() : properties.getOperations().getOnboardingMethod();
        putOnboardingExternalId(method, bizContent, request.outBizNo());
        AlipayGatewayResponse response = client.execute(channel, method, bizContent, options(request.appAuthToken(), null, null));
        return apiResponse(channel.getId(), response, null, null);
    }

    private GatewayResponse pagePay(
            PaymentGatewayProperties.Channel channel,
            PayCreateRequest request,
            String method,
            String productCode
    ) {
        Map<String, Object> bizContent = tradeBiz(channel, request);
        bizContent.put("product_code", productCode);
        String redirectHtml = client.pageForm(channel, method, bizContent, options(request));
        return new GatewayResponse(
                channel.getId(),
                PaymentStatus.CREATED,
                "PAGE_FORM_CREATED",
                "Alipay cashier form created",
                request.outTradeNo(),
                null,
                null,
                redirectHtml,
                null,
                Map.of("method", method),
                List.of()
        );
    }

    private GatewayResponse faceToFacePay(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        if (!hasText(request.authCode())) {
            return precreate(channel, request);
        }
        Map<String, Object> bizContent = tradeBiz(channel, request);
        bizContent.put("scene", "bar_code");
        bizContent.put("auth_code", request.authCode());
        bizContent.put("product_code", "FACE_TO_FACE_PAYMENT");
        AlipayGatewayResponse response = client.execute(channel, METHOD_TRADE_PAY, bizContent, options(request));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    private GatewayResponse precreate(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        Map<String, Object> bizContent = tradeBiz(channel, request);
        bizContent.put("product_code", "FACE_TO_FACE_PAYMENT");
        AlipayGatewayResponse response = client.execute(channel, METHOD_PRECREATE, bizContent, options(request));
        return apiResponse(channel.getId(), response, request.outTradeNo(), asString(response.response().get("qr_code")));
    }

    private GatewayResponse jsapi(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        Map<String, Object> bizContent = tradeBiz(channel, request);
        putIfText(bizContent, "buyer_id", request.buyerId());
        putIfText(bizContent, "buyer_open_id", request.buyerOpenId());
        bizContent.putIfAbsent("product_code", "JSAPI_PAY");
        AlipayGatewayResponse response = client.execute(channel, METHOD_TRADE_CREATE, bizContent, options(request));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    private GatewayResponse preauth(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        bizContent.put("out_order_no", request.outTradeNo());
        bizContent.put("out_request_no", valueFromExtra(request.extra(), "out_request_no", request.outTradeNo() + "_freeze"));
        bizContent.put("order_title", request.subject());
        bizContent.put("amount", amount(request.totalAmount()));
        bizContent.put("product_code", valueFromExtra(request.extra(), "product_code", "PRE_AUTH_ONLINE"));
        putIfText(bizContent, "payee_user_id", asString(valueFromExtra(request.extra(), "payee_user_id", null)));
        putIfText(bizContent, "payee_logon_id", asString(valueFromExtra(request.extra(), "payee_logon_id", null)));
        merge(bizContent, request.extra());
        removeInternalExtras(bizContent);
        AlipayGatewayResponse response = client.execute(channel, METHOD_PREAUTH_FREEZE, bizContent, options(request));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    private GatewayResponse directPay(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        Map<String, Object> bizContent = tradeBiz(channel, request);
        putIfPresent(bizContent, "settle_info", request.settleInfo());
        putIfPresent(bizContent, "royalty_info", request.royaltyInfo());
        String method = asString(valueFromExtra(request.extra(), "alipay_method", METHOD_TRADE_CREATE));
        bizContent.remove("alipay_method");
        AlipayGatewayResponse response = client.execute(channel, method, bizContent, options(request));
        return apiResponse(channel.getId(), response, request.outTradeNo(), null);
    }

    private Map<String, Object> tradeBiz(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        Map<String, Object> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", request.outTradeNo());
        bizContent.put("subject", request.subject());
        bizContent.put("total_amount", amount(request.totalAmount()));
        putIfText(bizContent, "quit_url", request.quitUrl());
        putIfText(bizContent, "timeout_express", request.timeoutExpress());
        putIfPresent(bizContent, "settle_info", request.settleInfo());
        putIfPresent(bizContent, "royalty_info", request.royaltyInfo());
        putDirectSubMerchant(channel, bizContent);
        merge(bizContent, request.extra());
        removeInternalExtras(bizContent);
        return bizContent;
    }

    private static void removeInternalExtras(Map<String, Object> bizContent) {
        bizContent.remove("cashier");
        bizContent.remove("cashierDesktopQr");
        bizContent.remove("cashierMobileDirect");
        bizContent.remove("cashierOriginalProduct");
        bizContent.remove("merchantId");
        bizContent.remove("merchantName");
    }

    private static void putDirectSubMerchant(PaymentGatewayProperties.Channel channel, Map<String, Object> bizContent) {
        if (!"ALIPAY_DIRECT".equals(channel.getProvider())) {
            return;
        }
        String smid = channel.getAlipay().getSubMerchantId();
        if (!hasText(smid)) {
            throw new GatewayException(
                    "ALIPAY_DIRECT_SMID_MISSING",
                    "Alipay Direct channel " + channel.getId() + " requires subMerchantId (SMID)"
            );
        }
        bizContent.putIfAbsent("sub_merchant", Map.of("merchant_id", smid));
    }

    private static void putOnboardingExternalId(String method, Map<String, Object> bizContent, String externalId) {
        if (METHOD_DIRECT_ZFT_SIMPLE_CREATE.equals(method)) {
            Object legacyOutBizNo = bizContent.remove("out_biz_no");
            String value = firstText(externalId, firstText(asString(bizContent.get("external_id")), asString(legacyOutBizNo)));
            putIfText(bizContent, "external_id", value);
            return;
        }
        bizContent.putIfAbsent("out_biz_no", externalId);
    }

    private GatewayResponse apiResponse(String channelId, AlipayGatewayResponse response, String fallbackOutTradeNo, String qrCode) {
        Map<String, Object> data = response.response();
        String outTradeNo = firstText(asString(data.get("out_trade_no")), fallbackOutTradeNo);
        String tradeNo = firstText(asString(data.get("trade_no")), asString(data.get("auth_no")));
        PaymentStatus status = status(response);
        String message = firstText(response.subMessage(), response.message());
        return new GatewayResponse(
                channelId,
                status,
                firstText(response.subCode(), response.code()),
                message,
                outTradeNo,
                tradeNo,
                qrCode,
                null,
                response.raw(),
                List.of()
        );
    }

    private static PaymentStatus status(AlipayGatewayResponse response) {
        String tradeStatus = asString(response.response().get("trade_status"));
        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            return PaymentStatus.SUCCESS;
        }
        if ("WAIT_BUYER_PAY".equals(tradeStatus)) {
            return PaymentStatus.PAYING;
        }
        if ("TRADE_CLOSED".equals(tradeStatus)) {
            return PaymentStatus.FAILED;
        }
        if ("10000".equals(response.code())) {
            if (METHOD_PRECREATE.equals(response.method())
                    || METHOD_TRADE_CREATE.equals(response.method())
                    || METHOD_PREAUTH_FREEZE.equals(response.method())) {
                return PaymentStatus.CREATED;
            }
            return PaymentStatus.SUCCESS;
        }
        if ("10003".equals(response.code())) {
            return PaymentStatus.PENDING;
        }
        return PaymentStatus.FAILED;
    }

    private static AlipayRequestOptions options(PayCreateRequest request) {
        return options(request.appAuthToken(), request.notifyUrl(), request.returnUrl());
    }

    private static AlipayRequestOptions options(String appAuthToken, String notifyUrl, String returnUrl) {
        return new AlipayRequestOptions(appAuthToken, notifyUrl, returnUrl);
    }

    private static void merge(Map<String, Object> target, Map<String, Object> extra) {
        if (extra != null) {
            target.putAll(extra);
        }
    }

    private static void putIfText(Map<String, Object> map, String key, String value) {
        if (hasText(value)) {
            map.put(key, value);
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static Object valueFromExtra(Map<String, Object> extra, String key, Object fallback) {
        if (extra == null || !extra.containsKey(key)) {
            return fallback;
        }
        Object value = extra.get(key);
        return value == null ? fallback : value;
    }

    private static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
