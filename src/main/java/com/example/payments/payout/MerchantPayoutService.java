package com.example.payments.payout;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.alipay.AlipayGatewayResponse;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.gateway.alipay.AlipayRequestOptions;
import com.example.payments.gateway.douyin.DouyinGatewayResponse;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.gateway.douyin.DouyinSignatureSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class MerchantPayoutService {

    public static final String PROVIDER_ALIPAY = "ALIPAY";
    public static final String PROVIDER_DOUYIN = "DOUYIN";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ALIPAY_PRODUCT_CODE = "TRANS_ACCOUNT_NO_PWD";
    private static final String ALIPAY_BIZ_SCENE = "DIRECT_TRANSFER";
    private static final String DOUYIN_TRANSFER_PATH = "/v1/fund_trade/mch-transfer/transfer-bills";
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final ChannelRegistry channelRegistry;
    private final AlipayOpenApiClient alipayClient;
    private final DouyinPayClient douyinClient;
    private final ObjectMapper objectMapper;

    public MerchantPayoutService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ChannelRegistry channelRegistry,
            AlipayOpenApiClient alipayClient,
            DouyinPayClient douyinClient,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.channelRegistry = channelRegistry;
        this.alipayClient = alipayClient;
        this.douyinClient = douyinClient;
        this.objectMapper = objectMapper;
    }

    public List<MerchantPayoutView> list(int limit) {
        requireDatabase();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query("""
                SELECT id, out_biz_no, provider, channel_id, recipient_type, recipient_masked,
                       recipient_name_masked, amount, order_title, remark, transfer_scene_id,
                       platform_order_no, platform_fund_order_no, status, code, message, fail_reason,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at,
                       DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at,
                       DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s') AS completed_at
                FROM merchant_payout
                ORDER BY id DESC
                LIMIT ?
                """, this::mapView, safeLimit);
    }

    public MerchantPayoutView create(MerchantPayoutCreateRequest request, String douyinNotifyUrl) {
        requireDatabase();
        PaymentGatewayProperties.Channel channel = channel(request.channelId());
        String provider = normalizedProvider(channel);
        BigDecimal amount = normalizedAmount(request.amount());
        String outBizNo = normalizedOutBizNo(request.outBizNo());
        validateProviderRequest(provider, channel, request, douyinNotifyUrl);
        if (PROVIDER_ALIPAY.equals(provider) && amount.compareTo(new BigDecimal("0.10")) < 0) {
            throw new IllegalArgumentException("支付宝商家转账单笔金额不能低于 0.10 元");
        }

        Map<String, Object> auditRequest = new LinkedHashMap<>();
        auditRequest.put("channel_id", channel.getId());
        auditRequest.put("recipient_type", normalizedRecipientType(provider, request.recipientType()));
        auditRequest.put("recipient_masked", mask(request.recipient()));
        auditRequest.put("amount", amount);
        auditRequest.put("order_title", firstText(request.orderTitle(), "商家代付"));
        auditRequest.put("remark", firstText(request.remark(), "商家代付"));
        insertPending(outBizNo, provider, channel, request, amount, auditRequest);

        try {
            if (PROVIDER_ALIPAY.equals(provider)) {
                AlipayGatewayResponse response = createAlipay(channel, request, outBizNo, amount);
                applyAlipayResponse(outBizNo, response);
            } else {
                DouyinGatewayResponse response = createDouyin(channel, request, outBizNo, amount, douyinNotifyUrl);
                applyDouyinResponse(outBizNo, response);
            }
        } catch (GatewayException ex) {
            if (outcomeUncertain(ex.code())) {
                markUnknown(outBizNo, ex.code(), ex.getMessage());
            } else {
                markFailed(outBizNo, ex.code(), ex.getMessage());
            }
        }
        return findRequired(outBizNo);
    }

    public MerchantPayoutView query(String outBizNo) {
        requireDatabase();
        MerchantPayoutView current = findRequired(cleanRequired(outBizNo, "outBizNo is required"));
        PaymentGatewayProperties.Channel channel = channel(current.channelId());
        try {
            if (PROVIDER_ALIPAY.equals(current.provider())) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("out_biz_no", current.outBizNo());
                body.put("product_code", ALIPAY_PRODUCT_CODE);
                body.put("biz_scene", ALIPAY_BIZ_SCENE);
                AlipayGatewayResponse response = alipayClient.execute(
                        channel,
                        "alipay.fund.trans.common.query",
                        body,
                        new AlipayRequestOptions(null, null, null)
                );
                applyAlipayResponse(current.outBizNo(), response);
            } else if (PROVIDER_DOUYIN.equals(current.provider())) {
                String path = DOUYIN_TRANSFER_PATH + "/out-bill-no/"
                        + URLEncoder.encode(current.outBizNo(), StandardCharsets.UTF_8);
                applyDouyinResponse(current.outBizNo(), douyinClient.get(channel, path));
            } else {
                throw new IllegalArgumentException("Unsupported payout provider: " + current.provider());
            }
        } catch (GatewayException ex) {
            markUnknown(current.outBizNo(), ex.code(), ex.getMessage());
        }
        return findRequired(current.outBizNo());
    }

    public void recordDouyinNotification(
            String channelId,
            String outBizNo,
            String platformOrderNo,
            String state,
            BigDecimal amount,
            String failReason,
            Map<String, Object> raw
    ) {
        MerchantPayoutView current = validatedNotification(PROVIDER_DOUYIN, channelId, outBizNo, amount);
        updateResult(
                current.outBizNo(),
                douyinStatus(state),
                platformOrderNo,
                null,
                null,
                state,
                failReason,
                raw
        );
    }

    public void recordAlipayNotification(
            String channelId,
            String outBizNo,
            String platformOrderNo,
            String platformFundOrderNo,
            String status,
            BigDecimal amount,
            Map<String, Object> raw
    ) {
        MerchantPayoutView current = validatedNotification(PROVIDER_ALIPAY, channelId, outBizNo, amount);
        updateResult(
                current.outBizNo(),
                alipayStatus(status),
                platformOrderNo,
                platformFundOrderNo,
                null,
                status,
                null,
                raw
        );
    }

    private AlipayGatewayResponse createAlipay(
            PaymentGatewayProperties.Channel channel,
            MerchantPayoutCreateRequest request,
            String outBizNo,
            BigDecimal amount
    ) {
        String identityType = normalizedRecipientType(PROVIDER_ALIPAY, request.recipientType());
        Map<String, Object> payee = new LinkedHashMap<>();
        payee.put("identity_type", identityType);
        payee.put("identity", request.recipient().trim());
        putIfText(payee, "name", request.recipientName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_biz_no", outBizNo);
        body.put("trans_amount", amount.toPlainString());
        body.put("product_code", ALIPAY_PRODUCT_CODE);
        body.put("biz_scene", ALIPAY_BIZ_SCENE);
        body.put("payee_info", payee);
        body.put("order_title", limit(firstText(request.orderTitle(), "商家代付"), 64));
        putIfText(body, "remark", limit(request.remark(), 200));
        return alipayClient.execute(
                channel,
                "alipay.fund.trans.uni.transfer",
                body,
                new AlipayRequestOptions(null, null, null)
        );
    }

    private DouyinGatewayResponse createDouyin(
            PaymentGatewayProperties.Channel channel,
            MerchantPayoutCreateRequest request,
            String outBizNo,
            BigDecimal amount,
            String notifyUrl
    ) {
        String recipientType = normalizedRecipientType(PROVIDER_DOUYIN, request.recipientType());
        String platformCertificate = channel.getDouyin().getPlatformCertificate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", channel.getDouyin().getAppId());
        body.put("out_bill_no", outBizNo);
        body.put("transfer_scene_id", request.transferSceneId().trim());
        if ("DOUYIN_PHONE".equals(recipientType)) {
            body.put("phone_number", DouyinSignatureSupport.encryptSensitive(request.recipient().trim(), platformCertificate));
        } else {
            body.put("openid", request.recipient().trim());
        }
        if (hasText(request.recipientName())) {
            body.put("user_name", DouyinSignatureSupport.encryptSensitive(request.recipientName().trim(), platformCertificate));
        }
        body.put("transfer_amount", amount.movePointRight(2).longValueExact());
        body.put("transfer_remark", limit(firstText(request.remark(), "商家代付"), 32));
        body.put("notify_url", notifyUrl);
        body.put("transfer_scene_report_infos", List.of(Map.of(
                "info_type", request.sceneInfoType().trim(),
                "info_content", request.sceneInfoContent().trim()
        )));
        return douyinClient.postSensitive(channel, DOUYIN_TRANSFER_PATH, body);
    }

    private void applyAlipayResponse(String outBizNo, AlipayGatewayResponse response) {
        Map<String, Object> body = response.response();
        String status = text(body, "status");
        String mapped = response.success() ? alipayStatus(status) : STATUS_FAILED;
        updateResult(
                outBizNo,
                mapped,
                text(body, "order_id"),
                text(body, "pay_fund_order_id"),
                response.code(),
                firstText(response.subMessage(), response.message()),
                null,
                response.raw()
        );
    }

    private void applyDouyinResponse(String outBizNo, DouyinGatewayResponse response) {
        Map<String, Object> body = response.body();
        Map<String, Object> data = nestedMap(body, "data");
        Map<String, Object> result = data.isEmpty() ? body : data;
        String state = firstText(text(result, "state"), text(result, "transfer_state"));
        updateResult(
                outBizNo,
                douyinStatus(state),
                text(result, "transfer_bill_no"),
                null,
                text(body, "code"),
                firstText(text(body, "message"), text(body, "msg"), state),
                text(result, "fail_reason"),
                body
        );
    }

    private void insertPending(
            String outBizNo,
            String provider,
            PaymentGatewayProperties.Channel channel,
            MerchantPayoutCreateRequest request,
            BigDecimal amount,
            Map<String, Object> auditRequest
    ) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO merchant_payout (
                        out_biz_no, provider, channel_id, recipient_type, recipient_masked,
                        recipient_name_masked, amount, order_title, remark, transfer_scene_id,
                        status, raw_request, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                    """,
                    outBizNo,
                    provider,
                    channel.getId(),
                    normalizedRecipientType(provider, request.recipientType()),
                    mask(request.recipient()),
                    maskName(request.recipientName()),
                    amount,
                    limit(firstText(request.orderTitle(), "商家代付"), 64),
                    limit(firstText(request.remark(), "商家代付"), provider.equals(PROVIDER_DOUYIN) ? 32 : 200),
                    trimToNull(request.transferSceneId()),
                    STATUS_PENDING,
                    json(auditRequest)
            );
        } catch (DataAccessException ex) {
            throw new IllegalArgumentException("代付单号已存在，或商家代付数据库表尚未导入", ex);
        }
    }

    private void updateResult(
            String outBizNo,
            String status,
            String platformOrderNo,
            String platformFundOrderNo,
            String code,
            String message,
            String failReason,
            Object rawResponse
    ) {
        MerchantPayoutView current = findRequired(outBizNo);
        String mergedStatus = mergeStatus(current.status(), status);
        jdbcTemplate.update("""
                UPDATE merchant_payout
                SET platform_order_no = COALESCE(?, platform_order_no),
                    platform_fund_order_no = COALESCE(?, platform_fund_order_no),
                    status = ?, code = ?, message = ?, fail_reason = ?, raw_response = ?,
                    completed_at = CASE WHEN ? IN ('SUCCESS', 'FAILED') THEN COALESCE(completed_at, NOW()) ELSE completed_at END,
                    updated_at = NOW()
                WHERE out_biz_no = ?
                """,
                trimToNull(platformOrderNo),
                trimToNull(platformFundOrderNo),
                mergedStatus,
                trimToNull(code),
                trimToNull(message),
                trimToNull(failReason),
                json(rawResponse),
                mergedStatus,
                outBizNo
        );
    }

    private void markUnknown(String outBizNo, String code, String message) {
        updateResult(outBizNo, STATUS_UNKNOWN, null, null, code, message, null, Map.of(
                "code", firstText(code, STATUS_UNKNOWN),
                "message", firstText(message, "接口结果待查询确认")
        ));
    }

    private void markFailed(String outBizNo, String code, String message) {
        updateResult(outBizNo, STATUS_FAILED, null, null, code, message, message, Map.of(
                "code", firstText(code, STATUS_FAILED),
                "message", firstText(message, "代付请求失败")
        ));
    }

    private MerchantPayoutView validatedNotification(
            String provider,
            String channelId,
            String outBizNo,
            BigDecimal amount
    ) {
        MerchantPayoutView current = findRequired(cleanRequired(outBizNo, "outBizNo is required"));
        if (!provider.equals(current.provider()) || !channelId.equals(current.channelId())) {
            throw new GatewayException("PAYOUT_NOTIFY_MISMATCH", "Payout notification provider or channel mismatch");
        }
        if (amount != null && current.amount().compareTo(normalizedAmount(amount)) != 0) {
            throw new GatewayException("PAYOUT_NOTIFY_AMOUNT_MISMATCH", "Payout notification amount mismatch");
        }
        return current;
    }

    private MerchantPayoutView findRequired(String outBizNo) {
        List<MerchantPayoutView> rows = jdbcTemplate.query("""
                SELECT id, out_biz_no, provider, channel_id, recipient_type, recipient_masked,
                       recipient_name_masked, amount, order_title, remark, transfer_scene_id,
                       platform_order_no, platform_fund_order_no, status, code, message, fail_reason,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at,
                       DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at,
                       DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s') AS completed_at
                FROM merchant_payout
                WHERE out_biz_no = ?
                """, this::mapView, outBizNo);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Unknown payout order: " + outBizNo);
        }
        return rows.getFirst();
    }

    private MerchantPayoutView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new MerchantPayoutView(
                rs.getLong("id"),
                rs.getString("out_biz_no"),
                rs.getString("provider"),
                rs.getString("channel_id"),
                rs.getString("recipient_type"),
                rs.getString("recipient_masked"),
                rs.getString("recipient_name_masked"),
                rs.getBigDecimal("amount"),
                rs.getString("order_title"),
                rs.getString("remark"),
                rs.getString("transfer_scene_id"),
                rs.getString("platform_order_no"),
                rs.getString("platform_fund_order_no"),
                rs.getString("status"),
                rs.getString("code"),
                rs.getString("message"),
                rs.getString("fail_reason"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("completed_at")
        );
    }

    private PaymentGatewayProperties.Channel channel(String channelId) {
        PaymentGatewayProperties.Channel channel = channelRegistry.find(cleanRequired(channelId, "channelId is required"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
        if (!channelRegistry.isEnabled(channel)) {
            throw new IllegalArgumentException("Payment channel is disabled: " + channel.getId());
        }
        return channel;
    }

    private static String normalizedProvider(PaymentGatewayProperties.Channel channel) {
        String provider = firstText(channel.getProvider(), "").toUpperCase(Locale.ROOT);
        if (!PROVIDER_ALIPAY.equals(provider) && !PROVIDER_DOUYIN.equals(provider)) {
            throw new IllegalArgumentException("商家代付仅支持支付宝普通通道和抖音支付通道");
        }
        return provider;
    }

    private static void validateProviderRequest(
            String provider,
            PaymentGatewayProperties.Channel channel,
            MerchantPayoutCreateRequest request,
            String douyinNotifyUrl
    ) {
        String recipientType = normalizedRecipientType(provider, request.recipientType());
        if (PROVIDER_ALIPAY.equals(provider)) {
            if (!"CERTIFICATE".equalsIgnoreCase(channel.getAlipay().getCredentialMode())) {
                throw new IllegalArgumentException("支付宝商家转账必须使用公钥证书模式");
            }
            if (hasText(channel.getAlipay().getAppAuthToken())) {
                throw new IllegalArgumentException("支付宝商家转账仅支持自研应用，所选通道不能配置 appAuthToken");
            }
            if ("ALIPAY_LOGON_ID".equals(recipientType) && !hasText(request.recipientName())) {
                throw new IllegalArgumentException("使用支付宝登录号收款时必须填写收款人真实姓名");
            }
            return;
        }
        if (!hasText(douyinNotifyUrl) || !douyinNotifyUrl.startsWith("https://")) {
            throw new IllegalArgumentException("抖音代付通知地址必须是公网 HTTPS 地址");
        }
        if (!hasText(request.transferSceneId())
                || !hasText(request.sceneInfoType())
                || !hasText(request.sceneInfoContent())) {
            throw new IllegalArgumentException("抖音代付必须填写已开通的转账场景 ID 和场景报备信息");
        }
    }

    static String normalizedRecipientType(String provider, String value) {
        String type = cleanRequired(value, "recipientType is required").toUpperCase(Locale.ROOT);
        if (PROVIDER_ALIPAY.equals(provider)
                && List.of("ALIPAY_USER_ID", "ALIPAY_LOGON_ID", "ALIPAY_OPEN_ID").contains(type)) {
            return type;
        }
        if (PROVIDER_DOUYIN.equals(provider)
                && List.of("DOUYIN_OPEN_ID", "DOUYIN_PHONE").contains(type)) {
            return type;
        }
        throw new IllegalArgumentException("收款标识类型与所选代付通道不匹配");
    }

    static String alipayStatus(String value) {
        String status = firstText(value, STATUS_PENDING).toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS" -> STATUS_SUCCESS;
            case "FAIL", "FAILED" -> STATUS_FAILED;
            case "DEALING", "PROCESSING", "PENDING" -> STATUS_PROCESSING;
            default -> STATUS_UNKNOWN;
        };
    }

    static String douyinStatus(String value) {
        String status = firstText(value, STATUS_PENDING).toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS" -> STATUS_SUCCESS;
            case "FAIL", "FAILED" -> STATUS_FAILED;
            case "ACCEPTED", "TRANSFERING", "PROCESSING", "PENDING" -> STATUS_PROCESSING;
            default -> STATUS_UNKNOWN;
        };
    }

    private static String mergeStatus(String current, String incoming) {
        if (STATUS_SUCCESS.equals(current)) {
            return STATUS_SUCCESS;
        }
        if (STATUS_FAILED.equals(current) && !STATUS_SUCCESS.equals(incoming)) {
            return STATUS_FAILED;
        }
        return firstText(incoming, current, STATUS_UNKNOWN);
    }

    private static String normalizedOutBizNo(String value) {
        String result = hasText(value)
                ? value.trim()
                : "PO" + LocalDateTime.now().format(ORDER_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Za-z0-9_*-]{6,32}")) {
            throw new IllegalArgumentException("代付单号必须为 6-32 位数字、字母、下划线、短横线或星号");
        }
        return result;
    }

    private static BigDecimal normalizedAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("代付金额必须大于 0");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("代付金额最多只能有两位小数", ex);
        }
    }

    private static boolean outcomeUncertain(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return value.endsWith("REQUEST_ERROR")
                || value.endsWith("REQUEST_INTERRUPTED")
                || value.endsWith("RESPONSE_INVALID")
                || value.endsWith("RESPONSE_SIGNATURE_INVALID")
                || value.startsWith("ALIPAY_HTTP_5")
                || value.startsWith("DOUYIN_HTTP_5");
    }

    private void requireDatabase() {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("商家代付必须启用数据库持久化");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize payout record", ex);
        }
    }

    private static void putIfText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String mask(String value) {
        if (!hasText(value)) {
            return null;
        }
        String text = value.trim();
        int at = text.indexOf('@');
        if (at > 0) {
            return text.substring(0, Math.min(2, at)) + "***" + text.substring(at);
        }
        if (text.length() <= 7) {
            return text.substring(0, 1) + "***" + text.substring(text.length() - 1);
        }
        return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
    }

    private static String maskName(String value) {
        if (!hasText(value)) {
            return null;
        }
        String name = value.trim();
        return name.substring(0, 1) + "**";
    }

    private static String limit(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String cleanRequired(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
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
