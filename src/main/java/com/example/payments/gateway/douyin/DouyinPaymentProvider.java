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
import com.example.payments.domain.ProfitSharingFinishRequest;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.ProfitSharingReturnQueryRequest;
import com.example.payments.domain.ProfitSharingReturnRequest;
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
    private static final String NATIVE_ORDER_PATH = "/v1/trade/transactions/native";
    private static final String QUERY_BY_OUT_TRADE_NO = "/v1/trade/transactions/out-trade-no/";
    private static final String QUERY_BY_TRANSACTION_ID = "/v1/trade/transactions/id/";
    private static final String REFUND_PATH = "/v1/trade/refund/domestic/refunds";
    private static final String PROFIT_SHARING_ORDER_PATH = "/v1/trade/profitsharing/orders";
    private static final String PROFIT_SHARING_RETURN_PATH = "/v1/trade/profitsharing/return-orders";
    private static final String PROFIT_SHARING_FINISH_PATH = "/v1/trade/profitsharing/finish-orders";
    private static final String PROFIT_SHARING_RECEIVER_ADD_PATH = "/v1/trade/profitsharing/receivers/add";
    private static final String PROFIT_SHARING_RECEIVER_DELETE_PATH = "/v1/trade/profitsharing/receivers/delete";

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
                && product != null
                && product.douyinPaymentProduct()
                && (channel.getProducts().isEmpty() || channel.getProducts().contains(product));
    }

    @Override
    public GatewayResponse pay(PaymentGatewayProperties.Channel channel, PayCreateRequest request) {
        PaymentGatewayProperties.Douyin config = channel.getDouyin();
        String notifyUrl = firstText(config.getNotifyUrl(), request.notifyUrl());
        if (!hasText(notifyUrl)) {
            throw new GatewayException("DOUYIN_NOTIFY_URL_MISSING", "Douyin Pay notify URL is required");
        }

        return switch (request.product()) {
            case DOUYIN_H5 -> h5Pay(channel, request, config, notifyUrl);
            case DOUYIN_NATIVE -> nativePay(channel, request, config, notifyUrl);
            default -> throw new GatewayException(
                    "UNSUPPORTED_PRODUCT",
                    "Douyin channel does not support product " + request.product()
            );
        };
    }

    private GatewayResponse h5Pay(
            PaymentGatewayProperties.Channel channel,
            PayCreateRequest request,
            PaymentGatewayProperties.Douyin config,
            String notifyUrl
    ) {

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
        putIfNotEmpty(body, "settle_info", request.settleInfo());
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

    private GatewayResponse nativePay(
            PaymentGatewayProperties.Channel channel,
            PayCreateRequest request,
            PaymentGatewayProperties.Douyin config,
            String notifyUrl
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", required(config.getAppId(), "Douyin Pay appId is required"));
        body.put("mchid", required(config.getMchId(), "Douyin Pay mchId is required"));
        body.put("description", request.subject());
        body.put("out_trade_no", request.outTradeNo());
        body.put("notify_url", notifyUrl);
        body.put("amount", amount(request.totalAmount()));
        putIfNotEmpty(body, "settle_info", request.settleInfo());
        mergeAllowedNativePayExtras(body, request.extra());

        DouyinGatewayResponse response = client.post(channel, NATIVE_ORDER_PATH, body);
        String codeUrl = firstText(
                text(response.body(), "code_url"),
                nestedText(response.body(), "data", "code_url")
        );
        if (!hasText(codeUrl)) {
            throw new GatewayException("DOUYIN_NATIVE_CODE_URL_MISSING", "Douyin Pay did not return code_url");
        }
        String transactionId = firstText(
                text(response.body(), "transaction_id"),
                nestedText(response.body(), "data", "transaction_id")
        );
        return new GatewayResponse(
                channel.getId(),
                PaymentStatus.PENDING,
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "message"), "Douyin Native order created"),
                request.outTradeNo(),
                transactionId,
                codeUrl,
                null,
                null,
                responseRaw(response, NATIVE_ORDER_PATH),
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
        body.put("appid", required(channel.getDouyin().getAppId(), "Douyin Pay appId is required"));
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        putIfText(body, "transaction_id", request.tradeNo());
        putIfText(body, "out_trade_no", request.outTradeNo());
        body.put("out_refund_no", request.outRequestNo());
        putIfText(body, "reason", request.refundReason());
        putIfText(body, "notify_url", firstText(extraText(request.extra(), "notify_url"), channel.getDouyin().getNotifyUrl()));
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
        String transactionId = required(request.tradeNo(), "Douyin Pay profit sharing requires transactionId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", required(channel.getDouyin().getAppId(), "Douyin Pay appId is required"));
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        body.put("transaction_id", transactionId);
        body.put("out_order_no", request.outRequestNo());
        body.put("receivers", douyinReceivers(channel, request.royaltyParameters()));
        body.put("unfreeze_unsplit", booleanExtra(request.extra(), "unfreeze_unsplit", false));
        putIfText(body, "notify_url", firstText(extraText(request.extra(), "notify_url"), channel.getDouyin().getNotifyUrl()));

        DouyinGatewayResponse response = client.postSensitive(channel, PROFIT_SHARING_ORDER_PATH, body);
        return profitSharingResponse(
                channel.getId(),
                response,
                request.outTradeNo(),
                transactionId,
                request.outRequestNo(),
                PROFIT_SHARING_ORDER_PATH
        );
    }

    @Override
    public GatewayResponse bindProfitSharingRelation(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingRelationBindRequest request
    ) {
        String type = douyinReceiverType(request.receiverType());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        body.put("appid", required(channel.getDouyin().getAppId(), "Douyin Pay appId is required"));
        body.put("type", type);
        body.put("account", request.receiverAccount());
        if (hasText(request.receiverName())) {
            body.put("name", DouyinSignatureSupport.encryptSensitive(
                    request.receiverName().trim(),
                    channel.getDouyin().getPlatformCertificate()
            ));
        } else if ("MERCHANT_ID".equals(type)) {
            throw new GatewayException("DOUYIN_RECEIVER_NAME_MISSING", "抖音商户分账接收方必须填写商户名称");
        }
        String relationType = firstText(extraText(request.extra(), "relation_type"), "PARTNER");
        body.put("relation_type", relationType);
        if ("CUSTOM".equals(relationType)) {
            body.put("custom_relation", required(
                    extraText(request.extra(), "custom_relation"),
                    "Douyin Pay custom relation is required when relation_type is CUSTOM"
            ));
        }

        DouyinGatewayResponse response = client.postSensitive(channel, PROFIT_SHARING_RECEIVER_ADD_PATH, body);
        return relationResponse(channel.getId(), response, request, "BOUND", PROFIT_SHARING_RECEIVER_ADD_PATH);
    }

    @Override
    public GatewayResponse queryProfitSharingRelations(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingRelationQueryRequest request
    ) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("provider", "DOUYIN");
        raw.put("message", "抖音支付未提供分账接收方列表查询接口，以下关系来自本系统已成功绑定记录");
        return new GatewayResponse(
                channel.getId(),
                PaymentStatus.SUCCESS,
                "SUCCESS",
                "已刷新本地抖音分账关系",
                null,
                null,
                null,
                null,
                raw,
                List.of()
        );
    }

    @Override
    public GatewayResponse unbindProfitSharingRelation(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingRelationBindRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        body.put("appid", required(channel.getDouyin().getAppId(), "Douyin Pay appId is required"));
        body.put("type", douyinReceiverType(request.receiverType()));
        body.put("account", request.receiverAccount());
        DouyinGatewayResponse response = client.post(channel, PROFIT_SHARING_RECEIVER_DELETE_PATH, body);
        return relationResponse(channel.getId(), response, request, "DISABLED", PROFIT_SHARING_RECEIVER_DELETE_PATH);
    }

    @Override
    public GatewayResponse queryProfitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingQueryRequest request) {
        String transactionId = required(request.tradeNo(), "Douyin Pay profit sharing query requires transactionId");
        String path = PROFIT_SHARING_ORDER_PATH + "/" + path(request.outRequestNo())
                + "?mchid=" + query(channel.getDouyin().getMchId())
                + "&transaction_id=" + query(transactionId);
        DouyinGatewayResponse response = client.get(channel, path);
        return profitSharingResponse(
                channel.getId(), response, request.outTradeNo(), transactionId, request.outRequestNo(), path
        );
    }

    @Override
    public GatewayResponse finishProfitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingFinishRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        body.put("transaction_id", request.tradeNo());
        body.put("out_order_no", request.outRequestNo());
        body.put("description", firstText(request.description(), "完成分账并解冻剩余资金"));
        putIfText(body, "notify_url", firstText(extraText(request.extra(), "notify_url"), channel.getDouyin().getNotifyUrl()));
        DouyinGatewayResponse response = client.post(channel, PROFIT_SHARING_FINISH_PATH, body);
        return profitSharingResponse(
                channel.getId(), response, request.outTradeNo(), request.tradeNo(), request.outRequestNo(), PROFIT_SHARING_FINISH_PATH
        );
    }

    @Override
    public GatewayResponse profitSharingRemainingAmount(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingQueryRequest request
    ) {
        String path = "/v1/trade/profitsharing/order/" + path(required(
                request.tradeNo(), "Douyin Pay remaining amount query requires transactionId"
        )) + "/amounts?mchid=" + query(channel.getDouyin().getMchId());
        DouyinGatewayResponse response = client.get(channel, path);
        return profitSharingResponse(
                channel.getId(), response, request.outTradeNo(), request.tradeNo(), request.outRequestNo(), path
        );
    }

    @Override
    public GatewayResponse returnProfitSharing(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingReturnRequest request
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mchid", required(channel.getDouyin().getMchId(), "Douyin Pay mchId is required"));
        body.put("out_order_no", request.outRequestNo());
        body.put("out_return_no", request.outReturnNo());
        body.put("return_mchid", request.receiverAccount());
        body.put("amount", fen(request.amount()));
        body.put("description", firstText(request.description(), "分账回退"));
        DouyinGatewayResponse response = client.post(channel, PROFIT_SHARING_RETURN_PATH, body);
        return profitSharingResponse(
                channel.getId(), response, null, null, request.outRequestNo(), PROFIT_SHARING_RETURN_PATH
        );
    }

    @Override
    public GatewayResponse queryProfitSharingReturn(
            PaymentGatewayProperties.Channel channel,
            ProfitSharingReturnQueryRequest request
    ) {
        String path = PROFIT_SHARING_RETURN_PATH + "/" + path(request.outReturnNo())
                + "?mchid=" + query(channel.getDouyin().getMchId())
                + "&out_order_no=" + query(request.outRequestNo());
        DouyinGatewayResponse response = client.get(channel, path);
        return profitSharingResponse(
                channel.getId(), response, null, null, request.outRequestNo(), path
        );
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
        if ("PAYERROR".equals(value)) {
            return PaymentStatus.FAILED;
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

    private static GatewayResponse profitSharingResponse(
            String channelId,
            DouyinGatewayResponse response,
            String outTradeNo,
            String transactionId,
            String outOrderNo,
            String requestPath
    ) {
        Map<String, Object> raw = responseRaw(response, requestPath);
        raw.putIfAbsent("profit_sharing_out_order_no", firstText(text(response.body(), "out_order_no"), outOrderNo));
        String state = firstText(
                text(response.body(), "state"),
                text(response.body(), "result"),
                nestedText(response.body(), "data", "state")
        );
        PaymentStatus status = switch (firstText(state, "PROCESSING")) {
            case "FINISHED", "SUCCESS" -> PaymentStatus.SUCCESS;
            case "CLOSED", "FAILED", "FAIL" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PENDING;
        };
        return new GatewayResponse(
                channelId,
                status,
                firstText(text(response.body(), "code"), state, "SUCCESS"),
                firstText(
                        text(response.body(), "message"),
                        text(response.body(), "fail_reason"),
                        status == PaymentStatus.SUCCESS ? "抖音分账处理完成" : "抖音分账请求已受理"
                ),
                outTradeNo,
                firstText(text(response.body(), "transaction_id"), transactionId),
                null,
                null,
                raw,
                List.of()
        );
    }

    private static GatewayResponse relationResponse(
            String channelId,
            DouyinGatewayResponse response,
            ProfitSharingRelationBindRequest request,
            String status,
            String requestPath
    ) {
        Map<String, Object> raw = responseRaw(response, requestPath);
        raw.putIfAbsent("account", request.receiverAccount());
        raw.putIfAbsent("type", douyinReceiverType(request.receiverType()));
        raw.putIfAbsent("name", request.receiverName());
        raw.put("status", status);
        return new GatewayResponse(
                channelId,
                PaymentStatus.SUCCESS,
                firstText(text(response.body(), "code"), "SUCCESS"),
                firstText(text(response.body(), "message"), "BOUND".equals(status) ? "抖音分账关系已添加" : "抖音分账关系已删除"),
                null,
                null,
                null,
                null,
                raw,
                List.of()
        );
    }

    private static List<Map<String, Object>> douyinReceivers(
            PaymentGatewayProperties.Channel channel,
            List<Map<String, Object>> royaltyParameters
    ) {
        if (royaltyParameters == null || royaltyParameters.isEmpty()) {
            throw new GatewayException("DOUYIN_RECEIVERS_MISSING", "抖音分账至少需要一个接收方");
        }
        return royaltyParameters.stream().map(parameter -> {
            Map<String, Object> receiver = new LinkedHashMap<>();
            receiver.put("type", douyinReceiverType(text(parameter, "trans_in_type")));
            receiver.put("account", required(text(parameter, "trans_in"), "Douyin Pay receiver account is required"));
            String receiverName = firstText(
                    text(parameter, "receiver_name"),
                    text(parameter, "receiverName"),
                    text(parameter, "name")
            );
            if (hasText(receiverName)) {
                receiver.put("name", DouyinSignatureSupport.encryptSensitive(
                        receiverName,
                        channel.getDouyin().getPlatformCertificate()
                ));
            }
            receiver.put("amount", fen(decimal(parameter.get("amount"))));
            receiver.put("description", firstText(text(parameter, "desc"), "订单分账"));
            return receiver;
        }).toList();
    }

    private static String douyinReceiverType(String value) {
        if ("PERSONAL_OPENID".equalsIgnoreCase(value)) {
            return "PERSONAL_OPENID";
        }
        if ("MERCHANT_ID".equalsIgnoreCase(value)) {
            return "MERCHANT_ID";
        }
        throw new GatewayException(
                "DOUYIN_RECEIVER_TYPE_INVALID",
                "抖音分账接收方类型必须是 MERCHANT_ID 或 PERSONAL_OPENID"
        );
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value == null || value.toString().isBlank()) {
            throw new GatewayException("DOUYIN_RECEIVER_AMOUNT_MISSING", "抖音分账接收方金额不能为空");
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            throw new GatewayException("DOUYIN_RECEIVER_AMOUNT_INVALID", "抖音分账接收方金额格式无效", ex);
        }
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
        copy(extra, body, "time_expire");
        copy(extra, body, "goods_tag");
        copy(extra, body, "attach");
        copy(extra, body, "support_fapiao");
        copy(extra, body, "detail");
        copy(extra, body, "settle_info");
    }

    private static void mergeAllowedNativePayExtras(Map<String, Object> body, Map<String, Object> extra) {
        if (extra == null) {
            return;
        }
        copy(extra, body, "time_expire");
        copy(extra, body, "attach");
        copy(extra, body, "goods_tag");
        copy(extra, body, "support_fapiao");
        copy(extra, body, "detail");
        copy(extra, body, "scene_info");
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

    private static boolean booleanExtra(Map<String, Object> extra, String key, boolean fallback) {
        Object value = extra == null ? null : extra.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static void putIfText(Map<String, Object> data, String key, String value) {
        if (hasText(value)) {
            data.put(key, value.trim());
        }
    }

    private static void putIfNotEmpty(Map<String, Object> data, String key, Map<String, Object> value) {
        if (value != null && !value.isEmpty()) {
            data.put(key, value);
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
