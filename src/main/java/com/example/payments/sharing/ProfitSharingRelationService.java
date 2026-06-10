package com.example.payments.sharing;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRelationQueryRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProfitSharingRelationService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, ProfitSharingRelationView> memoryRelations = new ConcurrentHashMap<>();

    public ProfitSharingRelationService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    public void recordBind(ProfitSharingRelationBindRequest request, GatewayResponse response) {
        if (request == null || response == null || response.status() == PaymentStatus.FAILED) {
            return;
        }
        String channelId = firstText(response.channelId(), firstChannel(request.channelIds()));
        if (!hasText(channelId) || !hasText(request.receiverAccount())) {
            return;
        }
        save(new ProfitSharingRelationView(
                channelId,
                firstText(request.receiverType(), "loginName"),
                request.receiverAccount().trim(),
                trimToNull(request.receiverName()),
                trimToNull(request.memo()),
                trimToNull(request.outRequestNo()),
                "BOUND",
                null
        ));
    }

    public void recordQuery(ProfitSharingRelationQueryRequest request, GatewayResponse response) {
        if (response == null || response.status() == PaymentStatus.FAILED || response.raw() == null) {
            return;
        }
        String channelId = firstText(response.channelId(), firstChannel(request == null ? null : request.channelIds()));
        if (!hasText(channelId)) {
            return;
        }
        for (Map<String, Object> candidate : relationCandidates(response.raw())) {
            String account = firstText(
                    stringValue(candidate, "account"),
                    stringValue(candidate, "receiver_account"),
                    stringValue(candidate, "receiverAccount"),
                    stringValue(candidate, "trans_in"),
                    stringValue(candidate, "transIn")
            );
            if (!hasText(account)) {
                continue;
            }
            save(new ProfitSharingRelationView(
                    channelId,
                    firstText(
                            stringValue(candidate, "type"),
                            stringValue(candidate, "receiver_type"),
                            stringValue(candidate, "receiverType"),
                            stringValue(candidate, "trans_in_type"),
                            stringValue(candidate, "transInType"),
                            "loginName"
                    ),
                    account.trim(),
                    firstText(
                            stringValue(candidate, "name"),
                            stringValue(candidate, "receiver_name"),
                            stringValue(candidate, "receiverName")
                    ),
                    firstText(stringValue(candidate, "memo"), stringValue(candidate, "desc")),
                    firstText(
                            stringValue(candidate, "out_request_no"),
                            stringValue(candidate, "outRequestNo")
                    ),
                    firstText(
                            stringValue(candidate, "status"),
                            stringValue(candidate, "relation_status"),
                            stringValue(candidate, "relationStatus"),
                            "BOUND"
                    ),
                    null
            ));
        }
    }

    public List<ProfitSharingRelationView> list(String channelId) {
        if (jdbcTemplate != null) {
            try {
                if (hasText(channelId)) {
                    return jdbcTemplate.query("""
                            SELECT channel_id, receiver_type, receiver_account, receiver_name, memo,
                                   out_request_no, status, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at
                            FROM profit_sharing_relation
                            WHERE channel_id = ?
                            ORDER BY updated_at DESC, id DESC
                            """, this::mapRow, channelId.trim());
                }
                return jdbcTemplate.query("""
                        SELECT channel_id, receiver_type, receiver_account, receiver_name, memo,
                               out_request_no, status, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at
                        FROM profit_sharing_relation
                        ORDER BY updated_at DESC, id DESC
                        """, this::mapRow);
            } catch (DataAccessException ignored) {
                // Keep the feature usable before the migration table is imported.
            }
        }
        return memoryRelations.values().stream()
                .filter(relation -> !hasText(channelId) || relation.channelId().equals(channelId.trim()))
                .sorted(Comparator.comparing(ProfitSharingRelationView::channelId)
                        .thenComparing(ProfitSharingRelationView::receiverAccount))
                .toList();
    }

    public boolean isBound(String channelId, String receiverType, String receiverAccount) {
        if (!hasText(channelId) || !hasText(receiverAccount)) {
            return false;
        }
        String safeType = firstText(receiverType, "loginName");
        String safeAccount = receiverAccount.trim();
        if (jdbcTemplate != null) {
            try {
                Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM profit_sharing_relation
                        WHERE channel_id = ?
                          AND receiver_type = ?
                          AND receiver_account = ?
                        """, Integer.class, channelId.trim(), safeType, safeAccount);
                return count != null && count > 0;
            } catch (DataAccessException ignored) {
                // Fall back to records captured in memory during the current process.
            }
        }
        return memoryRelations.containsKey(key(channelId, safeType, safeAccount));
    }

    private void save(ProfitSharingRelationView relation) {
        remember(relation);
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO profit_sharing_relation (
                        channel_id, receiver_type, receiver_account, receiver_name, memo,
                        out_request_no, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE
                        receiver_name = VALUES(receiver_name),
                        memo = VALUES(memo),
                        out_request_no = VALUES(out_request_no),
                        status = VALUES(status),
                        updated_at = NOW()
                    """,
                    relation.channelId(),
                    relation.receiverType(),
                    relation.receiverAccount(),
                    relation.receiverName(),
                    relation.memo(),
                    relation.outRequestNo(),
                    relation.status());
        } catch (DataAccessException ignored) {
            // The migration can be imported later without blocking live API calls.
        }
    }

    private void remember(ProfitSharingRelationView relation) {
        memoryRelations.put(
                key(relation.channelId(), relation.receiverType(), relation.receiverAccount()),
                relation
        );
    }

    private ProfitSharingRelationView mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProfitSharingRelationView(
                rs.getString("channel_id"),
                rs.getString("receiver_type"),
                rs.getString("receiver_account"),
                rs.getString("receiver_name"),
                rs.getString("memo"),
                rs.getString("out_request_no"),
                rs.getString("status"),
                rs.getString("updated_at")
        );
    }

    private static List<Map<String, Object>> relationCandidates(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        collectRelationCandidates(value, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void collectRelationCandidates(Object value, List<Map<String, Object>> result) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            if (hasAnyKey(normalized, "account", "receiver_account", "receiverAccount", "trans_in", "transIn")) {
                result.add(normalized);
            }
            normalized.values().forEach(item -> collectRelationCandidates(item, result));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectRelationCandidates(item, result));
        }
    }

    private static boolean hasAnyKey(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            if (value.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String stringValue(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    private static String firstChannel(List<String> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return null;
        }
        return channelIds.getFirst();
    }

    private static String key(String channelId, String receiverType, String receiverAccount) {
        return channelId.trim() + "|" + firstText(receiverType, "loginName") + "|" + receiverAccount.trim();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public record ProfitSharingRelationView(
            String channelId,
            String receiverType,
            String receiverAccount,
            String receiverName,
            String memo,
            String outRequestNo,
            String status,
            String updatedAt
    ) {
    }
}
