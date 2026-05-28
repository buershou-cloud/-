package com.example.payments.complaint;

import com.example.payments.domain.GatewayResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ComplaintRecordService {
    private static final int MEMORY_LIMIT = 300;
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] COMPLAINT_ID_KEYS = {
            "task_id", "taskId", "complaint_task_id", "complaintTaskId",
            "complaint_id", "complain_id", "complaintId", "complainId",
            "complain_event_id", "complainEventId", "complaint_event_id", "event_id", "record_id", "recordId", "id"
    };
    private static final String[] OUT_TRADE_NO_KEYS = {
            "out_trade_no", "outTradeNo", "out_no", "outNo", "merchant_order_no", "merchantOrderNo",
            "merchant_trade_no", "merchantTradeNo"
    };
    private static final String[] TRADE_NO_KEYS = {
            "trade_no", "tradeNo", "alipay_trade_no", "alipayTradeNo"
    };
    private static final String[] CONTENT_KEYS = {
            "complain_content", "complaint_content", "content", "content_text", "detail", "description", "memo",
            "user_complain_content", "user_complaint_content", "buyer_message", "user_message", "message_content",
            "feedback_content", "reply_content", "upgrade_content", "upgradeContent", "process_remark",
            "processRemark", "complaint_desc", "complain_desc", "problem_description", "issue_desc"
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentLinkedDeque<ComplaintRecordView> memoryRecords = new ConcurrentLinkedDeque<>();

    public ComplaintRecordService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public List<ComplaintRecordView> record(GatewayResponse response) {
        if (response == null) {
            return List.of();
        }
        List<ComplaintRecordView> records = extractRecords(response);
        records.forEach(record -> {
            remember(record);
            save(record, response.raw());
        });
        return List.copyOf(records);
    }

    public List<ComplaintRecordView> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.query("""
                        SELECT complaint_id, out_trade_no, trade_no, merchant_id, channel_id, status, title, content, queried_at
                        FROM complaint_record
                        WHERE complaint_id IS NULL OR complaint_id NOT LIKE 'QUERY-%'
                        ORDER BY queried_at DESC, id DESC
                        LIMIT ?
                        """, this::mapRow, safeLimit);
            } catch (DataAccessException ignored) {
                // If the database table is not imported yet, keep the UI useful with in-memory records.
            }
        }
        return memoryRecords.stream()
                .filter(record -> !isSyntheticQueryRecord(record))
                .limit(safeLimit)
                .toList();
    }

    private List<ComplaintRecordView> extractRecords(GatewayResponse response) {
        List<ComplaintRecordView> records = new ArrayList<>();
        collectRecords(response.raw(), response.channelId(), response.outTradeNo(), response.tradeNo(), records);
        return records;
    }

    @SuppressWarnings("unchecked")
    private void collectRecords(Object value, String channelId, String outTradeNo, String tradeNo,
            List<ComplaintRecordView> records) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> map.put(String.valueOf(key), item));
            ComplaintRecordView record = toRecord(map, channelId, outTradeNo, tradeNo);
            if (record != null) {
                records.add(record);
            }
            map.values().forEach(item -> collectRecords(item, value(map, channelId, "channel_id", "channelId"),
                    value(map, outTradeNo, OUT_TRADE_NO_KEYS),
                    value(map, tradeNo, TRADE_NO_KEYS), records));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectRecords(item, channelId, outTradeNo, tradeNo, records));
        }
    }

    private ComplaintRecordView toRecord(Map<String, Object> map, String channelId, String outTradeNo, String tradeNo) {
        if (isNestedComplaintTradeInfo(map)) {
            return null;
        }
        String complaintId = value(map, null, COMPLAINT_ID_KEYS);
        String directOutTradeNo = value(map, null, OUT_TRADE_NO_KEYS);
        String directTradeNo = value(map, null, TRADE_NO_KEYS);
        String recordOutTradeNo = firstText(directOutTradeNo, nestedValue(map, OUT_TRADE_NO_KEYS), outTradeNo);
        String recordTradeNo = firstText(directTradeNo, nestedValue(map, TRADE_NO_KEYS), tradeNo);
        String merchantId = value(map, null, "merchant_id", "merchantId", "sub_merchant_id", "smid",
                "opposite_pid", "oppositePid");
        String recordChannelId = value(map, channelId, "channel_id", "channelId");
        String status = value(map, null, "status", "complaint_status", "complain_status", "process_status",
                "complain_status_text", "complaint_status_text", "status_description", "statusDescription",
                "process_message", "processMessage");
        String title = value(map, null, "title", "subject", "reason", "complain_reason", "complaint_reason",
                "reason_desc", "opposite_name", "oppositeName", "process_message", "processMessage");
        String directContent = value(map, null, CONTENT_KEYS);
        String content = firstText(directContent, nestedValue(map, CONTENT_KEYS));
        String occurredAt = value(map, null, "gmt_create", "gmtCreate", "create_time", "createTime",
                "gmt_complain", "gmtComplain", "complain_time", "complaint_time", "event_time",
                "modified_time", "gmt_modified", "gmt_process", "gmtProcess", "gmt_overdue", "gmtOverdue",
                "gmt_upgrade", "gmtUpgrade", "gmt_risk_finish_time", "gmtRiskFinishTime");
        if (!isComplaintLike(map, complaintId, directOutTradeNo, directTradeNo, status, directContent)) {
            return null;
        }
        return new ComplaintRecordView(complaintId, recordOutTradeNo, recordTradeNo, merchantId, recordChannelId,
                textOr(status, "UNKNOWN"), textOr(title, "投诉查询结果"), textOr(content, title),
                textOr(occurredAt, LocalDateTime.now().format(TEXT_TIME)));
    }

    private boolean isComplaintLike(Map<String, Object> map, String complaintId, String outTradeNo, String tradeNo,
            String status, String content) {
        if (!hasText(complaintId) && !hasText(outTradeNo) && !hasText(tradeNo) && !hasText(status)) {
            return false;
        }
        if (isApiEnvelope(map) && !hasText(complaintId) && !hasText(outTradeNo) && !hasText(tradeNo)) {
            return false;
        }
        if (hasText(complaintId)) {
            return true;
        }
        if (hasText(content) && hasComplaintMarker(map)) {
            return true;
        }
        if (hasText(status) && (hasText(outTradeNo) || hasText(tradeNo))) {
            return true;
        }
        return hasText(content) && (hasText(outTradeNo) || hasText(tradeNo));
    }

    private boolean isNestedComplaintTradeInfo(Map<String, Object> map) {
        boolean hasTradeInfoShape = map.containsKey("complaint_record_id")
                || map.containsKey("complaintRecordId")
                || (map.containsKey("out_no") && map.containsKey("gmt_trade"));
        if (!hasTradeInfoShape) {
            return false;
        }
        return !hasText(value(map, null, "task_id", "taskId", "complain_content", "complaint_content",
                "upgrade_content", "opposite_pid", "oppositePid", "opposite_name", "oppositeName"));
    }

    private void remember(ComplaintRecordView record) {
        memoryRecords.addFirst(record);
        while (memoryRecords.size() > MEMORY_LIMIT) {
            memoryRecords.pollLast();
        }
    }

    private void save(ComplaintRecordView record, Map<String, Object> raw) {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO complaint_record
                    (complaint_id, out_trade_no, trade_no, merchant_id, channel_id, status, title, content, raw_response, queried_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                    out_trade_no = VALUES(out_trade_no),
                    trade_no = VALUES(trade_no),
                    merchant_id = VALUES(merchant_id),
                    channel_id = VALUES(channel_id),
                    status = VALUES(status),
                    title = VALUES(title),
                    content = VALUES(content),
                    raw_response = VALUES(raw_response),
                    queried_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                    """,
                    record.complaintId(),
                    existingValue("pay_order", "out_trade_no", record.outTradeNo()),
                    record.tradeNo(),
                    existingValue("merchant", "merchant_id", record.merchantId()),
                    existingValue("pay_channel", "id", record.channelId()),
                    record.status(),
                    record.title(),
                    record.content(),
                    rawToJson(raw));
        } catch (DataAccessException ignored) {
            // Memory fallback already contains the record.
        }
    }

    private String existingValue(String table, String column, String value) {
        if (!hasText(value) || jdbcTemplate == null) {
            return null;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                    Integer.class,
                    value);
            return count != null && count > 0 ? value : null;
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private String rawToJson(Map<String, Object> raw) {
        try {
            return objectMapper.writeValueAsString(raw == null ? Map.of() : raw);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private ComplaintRecordView mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp queriedAt = rs.getTimestamp("queried_at");
        return new ComplaintRecordView(
                rs.getString("complaint_id"),
                rs.getString("out_trade_no"),
                rs.getString("trade_no"),
                rs.getString("merchant_id"),
                rs.getString("channel_id"),
                rs.getString("status"),
                rs.getString("title"),
                rs.getString("content"),
                queriedAt == null ? "" : queriedAt.toLocalDateTime().format(TEXT_TIME));
    }

    private boolean isSyntheticQueryRecord(ComplaintRecordView record) {
        return record != null && hasText(record.complaintId()) && record.complaintId().startsWith("QUERY-");
    }

    private String value(Map<String, Object> map, String fallback, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    private String nestedValue(Object source, String... keys) {
        if (source instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> map.put(String.valueOf(key), item));
            String direct = value(map, null, keys);
            if (hasText(direct)) {
                return direct;
            }
            for (Object item : map.values()) {
                String nested = nestedValue(item, keys);
                if (hasText(nested)) {
                    return nested;
                }
            }
        }
        if (source instanceof Collection<?> collection) {
            for (Object item : collection) {
                String nested = nestedValue(item, keys);
                if (hasText(nested)) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean isApiEnvelope(Map<String, Object> map) {
        return map.containsKey("code") && (map.containsKey("msg") || map.containsKey("sub_msg"));
    }

    private boolean hasComplaintMarker(Map<String, Object> map) {
        return map.keySet().stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .anyMatch(key -> key.contains("complain") || key.contains("complaint"));
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String textOr(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ComplaintRecordView(
            String complaintId,
            String outTradeNo,
            String tradeNo,
            String merchantId,
            String channelId,
            String status,
            String title,
            String content,
            String queriedAt
    ) {
    }
}
