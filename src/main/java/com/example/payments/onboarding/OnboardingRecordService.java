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
        String status = statusOf(response);
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        return new OnboardingRecordView(
                request.outBizNo(),
                merchantName(payload),
                firstText(response.channelId(), firstAttemptChannel(response)),
                firstText(request.method(), "ant.merchant.expand.indirect.zft.simplecreate"),
                status,
                statusText(status),
                response.code(),
                response.message(),
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
        if (response.status() == PaymentStatus.SUCCESS) {
            return "SUCCESS";
        }
        if (response.status() == PaymentStatus.FAILED) {
            return "FAILED";
        }
        return "AUDITING";
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
            case "AUDITING" -> "审核中";
            default -> "待提交";
        };
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
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
