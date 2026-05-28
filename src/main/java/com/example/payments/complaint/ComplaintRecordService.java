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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ComplaintRecordService {
    private static final int MEMORY_LIMIT = 300;
    private static final DateTimeFormatter TEXT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        if (records.isEmpty()) {
            records = List.of(summaryRecord(response));
        }
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
                        ORDER BY queried_at DESC, id DESC
                        LIMIT ?
                        """, this::mapRow, safeLimit);
            } catch (DataAccessException ignored) {
                // If the database table is not imported yet, keep the UI useful with in-memory records.
            }
        }
        return memoryRecords.stream().limit(safeLimit).toList();
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
                    value(map, outTradeNo, "out_trade_no", "outTradeNo", "merchant_order_no"),
                    value(map, tradeNo, "trade_no", "tradeNo", "alipay_trade_no"), records));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectRecords(item, channelId, outTradeNo, tradeNo, records));
        }
    }

    private ComplaintRecordView toRecord(Map<String, Object> map, String channelId, String outTradeNo, String tradeNo) {
        String complaintId = value(map, null, "complaint_id", "complain_id", "complaintId", "complainId",
                "complain_event_id", "complainEventId", "complaint_event_id", "event_id", "id");
        String recordOutTradeNo = value(map, outTradeNo, "out_trade_no", "outTradeNo", "merchant_order_no");
        String recordTradeNo = value(map, tradeNo, "trade_no", "tradeNo", "alipay_trade_no");
        String merchantId = value(map, null, "merchant_id", "merchantId", "sub_merchant_id", "smid");
        String recordChannelId = value(map, channelId, "channel_id", "channelId");
        String status = value(map, null, "status", "complaint_status", "complain_status", "process_status");
        String title = value(map, null, "title", "subject", "reason", "code", "msg");
        String content = value(map, null, "content", "complaint_content", "detail", "description", "memo",
                "message", "sub_msg");
        if (!isComplaintLike(complaintId, recordOutTradeNo, recordTradeNo, status, content)) {
            return null;
        }
        if (!hasText(complaintId)) {
            complaintId = "QUERY-" + shortId();
        }
        return new ComplaintRecordView(complaintId, recordOutTradeNo, recordTradeNo, merchantId, recordChannelId,
                textOr(status, "UNKNOWN"), textOr(title, "投诉查询结果"), textOr(content, title),
                LocalDateTime.now().format(TEXT_TIME));
    }

    private boolean isComplaintLike(String complaintId, String outTradeNo, String tradeNo, String status,
            String content) {
        if (hasText(complaintId)) {
            return true;
        }
        if (hasText(status) && (hasText(content) || hasText(outTradeNo) || hasText(tradeNo))) {
            return true;
        }
        return hasText(content) && (hasText(outTradeNo) || hasText(tradeNo));
    }

    private ComplaintRecordView summaryRecord(GatewayResponse response) {
        String title = hasText(response.code()) ? response.code() : "投诉查询结果";
        String content = hasText(response.message()) ? response.message() : "支付宝未返回投诉内容";
        String status = response.status() == null ? "UNKNOWN" : response.status().name();
        return new ComplaintRecordView("QUERY-" + shortId(), response.outTradeNo(), response.tradeNo(), null,
                response.channelId(), status, title, content, LocalDateTime.now().format(TEXT_TIME));
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

    private String value(Map<String, Object> map, String fallback, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return fallback;
    }

    private String textOr(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
