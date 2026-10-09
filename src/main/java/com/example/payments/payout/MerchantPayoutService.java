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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final Map<String, List<String>> DOUYIN_SCENE_REPORT_TYPES = Map.of(
            "1001", List.of("活动名称", "奖励说明"),
            "1002", List.of("赔付原因"),
            "1003", List.of("岗位类型", "报酬说明"),
            "1004", List.of("采购商品名称"),
            "1005", List.of("回收商品名称"),
            "1006", List.of("公益活动名称", "公益活动备案编号"),
            "1007", List.of("补贴类型")
    );

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

    /** Search persisted payout history without limiting it to the latest payout-page records. */
    public List<MerchantPayoutView> search(String beginTime, String endTime, String orderNo, String tradeNo, String channelId) {
        requireDatabase();
        StringBuilder sql = new StringBuilder("""
                SELECT id, out_biz_no, provider, channel_id, recipient_type, recipient_masked,
                       recipient_name_masked, amount, order_title, remark, transfer_scene_id,
                       platform_order_no, platform_fund_order_no, status, code, message, fail_reason,
                       DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at,
                       DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at,
                       DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s') AS completed_at
                FROM merchant_payout
                WHERE 1 = 1
                """);
        List<Object> arguments = new ArrayList<>();
        if (hasText(beginTime)) {
            sql.append(" AND created_at >= ?");
            arguments.add(searchTime(beginTime));
        }
        if (hasText(endTime)) {
            sql.append(" AND created_at <= ?");
            arguments.add(searchTime(endTime));
        }
        if (hasText(orderNo)) {
            sql.append(" AND out_biz_no LIKE ?");
            arguments.add("%" + orderNo.trim() + "%");
        }
        if (hasText(tradeNo)) {
            sql.append(" AND (platform_order_no LIKE ? OR platform_fund_order_no LIKE ?)");
            arguments.add("%" + tradeNo.trim() + "%");
            arguments.add("%" + tradeNo.trim() + "%");
        }
        if (hasText(channelId)) {
            sql.append(" AND channel_id = ?");
            arguments.add(channelId.trim());
        }
        sql.append(" ORDER BY created_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), this::mapView, arguments.toArray());
    }

    private static Timestamp searchTime(String value) {
        String normalized = value.trim().replace('T', ' ');
        try {
            return Timestamp.valueOf(normalized.length() == 16 ? normalized + ":00" : normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("代付查询时间格式应为 yyyy-MM-dd HH:mm[:ss]", ex);
        }
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
                applyAlipayResponse(outBizNo, response, false);
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
            String observedStatus;
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
                observedStatus = applyAlipayResponse(current.outBizNo(), response, true);
            } else if (PROVIDER_DOUYIN.equals(current.provider())) {
                String path = DOUYIN_TRANSFER_PATH + "/out-bill-no/"
                        + URLEncoder.encode(current.outBizNo(), StandardCharsets.UTF_8);
                observedStatus = applyDouyinResponse(current.outBizNo(), douyinClient.get(channel, path));
            } else {
                throw new IllegalArgumentException("Unsupported payout provider: " + current.provider());
            }
            MerchantPayoutView saved = findRequired(current.outBizNo());
            if (!observedStatus.equals(saved.status())) {
                throw new GatewayException("PAYOUT_QUERY_STATE_CONFLICT", "本次返回状态 " + observedStatus
                        + " 与本地已确认状态 " + saved.status() + " 不一致，保留已确认记录并核对原单");
            }
            return saved;
        } catch (GatewayException ex) {
            markUnknown(current.outBizNo(), ex.code(), ex.getMessage());
            MerchantPayoutView saved = findRequired(current.outBizNo());
            throw new GatewayException("PAYOUT_QUERY_UNCONFIRMED",
                    "本次原单查询未能确认结果（" + ex.code() + "）：" + ex.getMessage()
                            + "；本地记录状态为 " + saved.status() + "，请勿重复代付。", ex);
        }
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
        body.put("transfer_scene_report_infos", douyinSceneReportInfos(request));
        return douyinClient.postSensitive(channel, DOUYIN_TRANSFER_PATH, body);
    }

    private String applyAlipayResponse(String outBizNo, AlipayGatewayResponse response, boolean query) {
        String expectedKey = query ? "alipay_fund_trans_common_query_response" : "alipay_fund_trans_uni_transfer_response";
        boolean errorEnvelope = "error_response".equals(response.responseKey()) && !response.success()
                && hasText(response.code()) && !"10000".equals(response.code());
        if ((!expectedKey.equals(response.responseKey()) && !errorEnvelope)
                || response.response() == null || !hasText(response.code())) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "支付宝代付回包缺少对应接口的有效结果，请核对原单");
        }
        if (!response.success() && query) {
            throw new GatewayException(firstText(response.subCode(), response.code(), "ALIPAY_QUERY_ERROR"),
                    firstText(response.subMessage(), response.message(), "支付宝未返回可确认的原单结果"));
        }
        if (!response.success() && "20000".equals(response.code())) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "支付宝返回系统异常，转账结果待核对："
                    + firstText(response.subMessage(), response.message(), response.code()));
        }
        Map<String, Object> body = response.response();
        MerchantPayoutView current = findRequired(outBizNo);
        validateResponseIdentity(current, body, "out_biz_no", "trans_amount", false);
        validateResponseAmount(current, body, "amount", false);
        String platformOrderNo = responseIdentifier(body, "order_id");
        String platformFundOrderNo = responseIdentifier(body, "pay_fund_order_id");
        validatePlatformIdentifiers(current, platformOrderNo, platformFundOrderNo);
        String status = responseIdentifier(body, "status");
        String mapped = response.success() ? alipayStatus(status) : STATUS_FAILED;
        if (STATUS_UNKNOWN.equals(mapped)) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "支付宝代付回包缺少有效转账状态，请核对原单");
        }
        String failureReason = null;
        if (STATUS_FAILED.equals(mapped)) {
            String errorCode = trimToNull(text(body, "error_code"));
            String reason = firstText(text(body, "fail_reason"), response.subMessage(), response.message());
            failureReason = errorCode == null ? reason : "[" + errorCode + "] " + firstText(reason, "转账失败");
        }
        updateResult(
                outBizNo,
                mapped,
                platformOrderNo,
                platformFundOrderNo,
                response.code(),
                firstText(failureReason, response.subMessage(), response.message()),
                failureReason,
                response.raw()
        );
        return mapped;
    }

    private String applyDouyinResponse(String outBizNo, DouyinGatewayResponse response) {
        Map<String, Object> body = response.body();
        Map<String, Object> data = nestedMap(body, "data");
        Map<String, Object> result = data.isEmpty() ? body : data;
        MerchantPayoutView current = findRequired(outBizNo);
        validateResponseIdentity(current, result, "out_bill_no", "transfer_amount", true);
        String platformOrderNo = responseIdentifier(result, "transfer_bill_no");
        validatePlatformIdentifiers(current, platformOrderNo, null);
        String state = firstText(responseIdentifier(result, "state"), responseIdentifier(result, "transfer_state"));
        String mapped = douyinStatus(state);
        if (STATUS_UNKNOWN.equals(mapped)) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "抖音代付回包缺少有效转账状态，请核对原单");
        }
        updateResult(
                outBizNo,
                mapped,
                platformOrderNo,
                null,
                text(body, "code"),
                firstText(text(body, "message"), text(body, "msg"), state),
                text(result, "fail_reason"),
                body
        );
        return mapped;
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
                    persistedTransferSceneId(provider, request.transferSceneId()),
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
        String incomingStatus = firstText(status, STATUS_UNKNOWN);
        // The terminal-state guard is evaluated by the database while the row is locked.
        // A response read before a successful callback must not overwrite that callback later.
        int updated = jdbcTemplate.update("""
                UPDATE merchant_payout
                SET platform_order_no = COALESCE(?, platform_order_no),
                    platform_fund_order_no = COALESCE(?, platform_fund_order_no),
                    status = ?, code = ?, message = ?, fail_reason = ?, raw_response = ?,
                    completed_at = CASE WHEN ? IN ('SUCCESS', 'FAILED') THEN COALESCE(completed_at, NOW()) ELSE completed_at END,
                    updated_at = NOW()
                WHERE out_biz_no = ?
                  AND (status NOT IN ('SUCCESS', 'FAILED')
                       OR ? = 'SUCCESS'
                       OR (status = 'FAILED' AND ? = 'FAILED'))
                  AND (platform_order_no IS NULL OR ? IS NULL OR platform_order_no = ?)
                  AND (platform_fund_order_no IS NULL OR ? IS NULL OR platform_fund_order_no = ?)
                """,
                trimToNull(platformOrderNo),
                trimToNull(platformFundOrderNo),
                incomingStatus,
                trimToNull(code),
                trimToNull(message),
                trimToNull(failReason),
                json(rawResponse),
                incomingStatus,
                outBizNo,
                incomingStatus,
                incomingStatus,
                trimToNull(platformOrderNo), trimToNull(platformOrderNo),
                trimToNull(platformFundOrderNo), trimToNull(platformFundOrderNo)
        );
        if (updated == 0) {
            // A rejected stale update is expected; a missing local order is still an error.
            validatePlatformIdentifiers(findRequired(outBizNo), platformOrderNo, platformFundOrderNo);
        }
    }

    private static void validateResponseIdentity(MerchantPayoutView current, Map<String, Object> body,
                                                 String orderKey, String amountKey, boolean fen) {
        String order = responseIdentifier(body, orderKey);
        if (order != null && !current.outBizNo().equals(order)) {
            throw new GatewayException("PAYOUT_RESPONSE_MISMATCH", "代付回包的原单号与本地记录不一致");
        }
        validateResponseAmount(current, body, amountKey, fen);
    }

    private static void validateResponseAmount(MerchantPayoutView current, Map<String, Object> body, String key, boolean fen) {
        Object value = body.get(key);
        if (value == null || (value instanceof String text && text.isBlank())) return;
        try {
            if (!(value instanceof String) && !(value instanceof Number)) throw new NumberFormatException();
            BigDecimal amount = new BigDecimal(value.toString());
            if (fen) amount = amount.setScale(0, RoundingMode.UNNECESSARY).movePointLeft(2);
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(current.amount()) != 0) {
                throw new GatewayException("PAYOUT_RESPONSE_MISMATCH", "代付回包的金额与本地记录不一致");
            }
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "代付回包金额格式无效", ex);
        }
    }

    private static String responseIdentifier(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new GatewayException("PAYOUT_RESPONSE_INVALID", "代付回包字段格式无效：" + key);
        }
        return trimToNull(text);
    }

    private static void validatePlatformIdentifiers(MerchantPayoutView current, String platform, String fund) {
        if ((hasText(platform) && hasText(current.platformOrderNo()) && !platform.trim().equals(current.platformOrderNo()))
                || (hasText(fund) && hasText(current.platformFundOrderNo()) && !fund.trim().equals(current.platformFundOrderNo()))) {
            throw new GatewayException("PAYOUT_RESPONSE_MISMATCH", "代付回包的平台单号与本地记录不一致");
        }
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
        if (!hasText(request.transferSceneId())) {
            throw new IllegalArgumentException("抖音代付必须填写已开通的转账场景 ID 和场景报备信息");
        }
        douyinSceneReportInfos(request);
    }

    static List<Map<String, String>> douyinSceneReportInfos(MerchantPayoutCreateRequest request) {
        List<MerchantPayoutSceneReportInfoRequest> source = request.sceneReportInfos();
        if (source == null || source.isEmpty()) {
            if (!hasText(request.sceneInfoType()) || !hasText(request.sceneInfoContent())) {
                throw new IllegalArgumentException("抖音代付必须完整填写转账场景报备信息");
            }
            source = List.of(new MerchantPayoutSceneReportInfoRequest(
                    request.sceneInfoType(),
                    request.sceneInfoContent()
            ));
        }

        List<Map<String, String>> result = new ArrayList<>();
        for (MerchantPayoutSceneReportInfoRequest info : source) {
            if (info == null || !hasText(info.infoType()) || !hasText(info.infoContent())) {
                throw new IllegalArgumentException("抖音代付的每条场景报备信息都必须填写类型和内容");
            }
            String infoType = info.infoType().trim();
            String infoContent = info.infoContent().trim();
            if (infoType.length() > 15) {
                throw new IllegalArgumentException("抖音代付场景报备类型不能超过 15 个字符");
            }
            if (infoContent.length() > 32) {
                throw new IllegalArgumentException("抖音代付场景报备内容不能超过 32 个字符");
            }
            result.add(Map.of("info_type", infoType, "info_content", infoContent));
        }

        List<String> expectedTypes = DOUYIN_SCENE_REPORT_TYPES.get(request.transferSceneId().trim());
        if (expectedTypes != null) {
            List<String> actualTypes = result.stream().map(item -> item.get("info_type")).toList();
            if (actualTypes.size() != expectedTypes.size()
                    || !actualTypes.containsAll(expectedTypes)
                    || !expectedTypes.containsAll(actualTypes)) {
                throw new IllegalArgumentException(
                        "抖音转账场景 " + request.transferSceneId().trim()
                                + " 必须填写以下报备类型：" + String.join("、", expectedTypes)
                );
            }
        }
        return List.copyOf(result);
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

    static String persistedTransferSceneId(String provider, String value) {
        return PROVIDER_DOUYIN.equals(provider) ? trimToNull(value) : null;
    }

    static String alipayStatus(String value) {
        String status = firstText(value, STATUS_UNKNOWN).toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS" -> STATUS_SUCCESS;
            case "FAIL", "FAILED" -> STATUS_FAILED;
            case "DEALING", "PROCESSING", "PENDING" -> STATUS_PROCESSING;
            default -> STATUS_UNKNOWN;
        };
    }

    static String douyinStatus(String value) {
        String status = firstText(value, STATUS_UNKNOWN).toUpperCase(Locale.ROOT);
        return switch (status) {
            case "SUCCESS" -> STATUS_SUCCESS;
            case "FAIL", "FAILED" -> STATUS_FAILED;
            case "ACCEPTED", "TRANSFERING", "PROCESSING", "PENDING" -> STATUS_PROCESSING;
            default -> STATUS_UNKNOWN;
        };
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
                || value.equals("ALIPAY_PARSE_ERROR")
                || value.equals("PAYOUT_RESPONSE_MISMATCH")
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
