package com.example.payments.onboarding;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PaymentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OnboardingRecordService {

    private static final int LIST_LIMIT = 100;
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final List<OnboardingRecordView> memoryRecords = new CopyOnWriteArrayList<>();

    public OnboardingRecordService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public List<OnboardingRecordView> list() {
        if (jdbcTemplate == null) {
            return List.copyOf(memoryRecords);
        }
        try {
            return jdbcTemplate.query("""
                    SELECT out_biz_no, channel_id, method_name, request_body, status, code, message, created_at
                    FROM onboarding_record
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                    """, (rs, rowNum) -> {
                String requestBody = rs.getString("request_body");
                Map<String, Object> payload = readMap(requestBody);
                String status = rs.getString("status");
                return new OnboardingRecordView(
                        rs.getString("out_biz_no"),
                        merchantName(payload),
                        rs.getString("channel_id"),
                        rs.getString("method_name"),
                        status,
                        statusText(status),
                        rs.getString("code"),
                        rs.getString("message"),
                        timestampText(rs.getTimestamp("created_at"))
                );
            }, LIST_LIMIT);
        } catch (DataAccessException ex) {
            return List.copyOf(memoryRecords);
        }
    }

    public void record(OnboardingRequest request, GatewayResponse response) {
        OnboardingRecordView view = viewFrom(request, response);
        upsertMemory(view);
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO onboarding_record
                      (out_biz_no, channel_id, method_name, app_auth_token, request_body, response_body, status, code, message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      channel_id = VALUES(channel_id),
                      method_name = VALUES(method_name),
                      app_auth_token = VALUES(app_auth_token),
                      request_body = VALUES(request_body),
                      response_body = VALUES(response_body),
                      status = VALUES(status),
                      code = VALUES(code),
                      message = VALUES(message),
                      updated_at = CURRENT_TIMESTAMP
                    """,
                    view.outBizNo(),
                    view.channelId(),
                    view.method(),
                    blankToNull(request.appAuthToken()),
                    json(request.payload()),
                    json(response),
                    view.status(),
                    view.code(),
                    view.message()
            );
        } catch (DataAccessException ex) {
            // Keep onboarding usable even when an older database has not imported the record table yet.
        }
    }

    private OnboardingRecordView viewFrom(OnboardingRequest request, GatewayResponse response) {
        OnboardingState latestState = stateFrom(response);
        String status = firstText(latestState.status(), statusOf(response));
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        return new OnboardingRecordView(
                request.outBizNo(),
                merchantName(payload),
                firstText(response.channelId(), firstAttemptChannel(response)),
                firstText(request.method(), "ant.merchant.expand.indirect.zft.simplecreate"),
                status,
                statusText(status),
                firstText(latestState.code(), response.code()),
                firstText(latestState.message(), response.message()),
                LocalDateTime.now().format(DISPLAY_TIME)
        );
    }

    private void upsertMemory(OnboardingRecordView view) {
        memoryRecords.removeIf(record -> Objects.equals(record.outBizNo(), view.outBizNo()));
        memoryRecords.add(0, view);
        while (memoryRecords.size() > LIST_LIMIT) {
            memoryRecords.remove(memoryRecords.size() - 1);
        }
    }

    private String statusOf(GatewayResponse response) {
        String requestMethod = text(response.raw() == null ? null : response.raw().get("request_method"));
        if (response.status() != PaymentStatus.FAILED
                && (requestMethod.endsWith(".delete") || requestMethod.endsWith(".cancel"))) {
            return "CANCELLED";
        }
        if (response.status() == PaymentStatus.SUCCESS) {
            return "SUCCESS";
        }
        if (response.status() == PaymentStatus.FAILED) {
            return "FAILED";
        }
        return "AUDITING";
    }

    private OnboardingState stateFrom(GatewayResponse response) {
        Map<String, Object> apiResponse = apiResponse(response.raw());
        Map<String, Object> order = firstOrder(firstValue(apiResponse, "orders", "zft_sub_merchant_order"));
        if (order.isEmpty()) {
            return new OnboardingState(null, null, null);
        }
        String alipayStatus = text(order.get("status"));
        String status = normalizeAlipayStatus(alipayStatus);
        String code = firstText(alipayStatus, response.code());
        return new OnboardingState(status, code, orderMessage(order));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiResponse(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey().endsWith("_response") && entry.getValue() instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOrder(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String normalizeAlipayStatus(String status) {
        if (!hasText(status)) {
            return null;
        }
        String upper = status.trim().toUpperCase();
        if (upper.contains("CANCEL") || upper.contains("CLOSE") || upper.contains("DELETE")) {
            return "CANCELLED";
        }
        if (upper.contains("FAIL") || upper.contains("REJECT") || upper.contains("REFUSE") || upper.contains("UNPASS")) {
            return "FAILED";
        }
        if (upper.contains("SUCCESS") || upper.contains("PASS") || upper.contains("EFFECTIVE")
                || upper.contains("FINISH") || upper.contains("COMPLETE") || upper.contains("SIGNED")) {
            return "SUCCESS";
        }
        return "AUDITING";
    }

    private static String orderMessage(Map<String, Object> order) {
        List<String> parts = new ArrayList<>();
        appendPart(parts, "状态", text(order.get("status")));
        appendPart(parts, "订单号", text(order.get("order_id")));
        appendPart(parts, "商户", text(order.get("merchant_name")));
        appendPart(parts, "SMID", text(order.get("smid")));
        appendPart(parts, "原因", text(order.get("reason")));
        appendPart(parts, "风控审核", text(order.get("fk_audit")));
        appendPart(parts, "风控备注", text(order.get("fk_audit_memo")));
        appendPart(parts, "扩展审核", text(order.get("kz_audit")));
        appendPart(parts, "扩展备注", text(order.get("kz_audit_memo")));
        appendPart(parts, "签约短链", text(order.get("sub_sign_short_chain_url")));
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static void appendPart(List<String> parts, String label, String value) {
        if (hasText(value)) {
            parts.add(label + ":" + value.trim());
        }
    }

    private String firstAttemptChannel(GatewayResponse response) {
        if (response.attempts() == null || response.attempts().isEmpty()) {
            return null;
        }
        return response.attempts().getFirst().channelId();
    }

    private String merchantName(Map<String, Object> payload) {
        String aliasName = text(payload.get("alias_name"));
        if (hasText(aliasName)) {
            return aliasName;
        }
        String name = text(payload.get("name"));
        if (hasText(name)) {
            return name;
        }
        String externalId = text(payload.get("external_id"));
        return hasText(externalId) ? externalId : "-";
    }

    private Map<String, Object> readMap(String json) {
        if (!hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String timestampText(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private String statusText(String status) {
        return switch (firstText(status, "PENDING")) {
            case "SUCCESS" -> "已通过";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            case "AUDITING" -> "审核中";
            default -> "待提交";
        };
    }

    private record OnboardingState(String status, String code, String message) {
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record OnboardingRecordView(
            String outBizNo,
            String merchantName,
            String channelId,
            String method,
            String status,
            String statusText,
            String code,
            String message,
            String createdAt
    ) {}
}
