package com.example.payments.sharing;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.order.DemoOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProfitSharingRecordServiceTest {
    private JdbcTemplate jdbc;
    private DemoOrderService orders;
    private ProfitSharingRecordService records;
    private PaymentGatewayProperties.Channel channel;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        orders = new DemoOrderService();
        records = new ProfitSharingRecordService(jdbc, orders, new ObjectMapper());
        channel = new PaymentGatewayProperties.Channel();
        channel.setId("douyin");
        channel.setProvider("DOUYIN");
    }

    @Test
    void completedExternalSplitSurvivesServiceRestartWithoutInventingPaymentOrder() {
        ProfitSharingRequest request = request("SHARE-1", "2.34");
        assertThat(records.reserve(channel, request)).isNull();
        records.recordResponse(channel, request, result(PaymentStatus.SUCCESS, Map.of("out_order_no", "SHARE-1")));

        ProfitSharingRecordService restarted = new ProfitSharingRecordService(jdbc, orders, new ObjectMapper());
        var result = restarted.search(null, null, "SHARE-1", "REMOTE-TRADE", "douyin");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().status()).isEqualTo("SUCCESS");
        assertThat(result.getFirst().recordType()).isEqualTo("PROFIT_SHARING");
        assertThat(result.getFirst().amount()).isEqualByComparingTo("2.34");
        assertThat(result.getFirst().recipient()).isEqualTo("re***01");
        assertThat(result.getFirst().relatedOutTradeNo()).isNull();
        assertThat(orders.recent()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT raw_request FROM profit_sharing_record", String.class)).doesNotContain("secret-app-token");
        assertThat(restarted.reserve(channel, request).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void lateHttpResponseCannotUndoSuccessWrittenByAnotherInstance() {
        ProfitSharingRequest request = request("SHARE-1", "2.34");
        records.reserve(channel, request);
        ProfitSharingRecordService callback = new ProfitSharingRecordService(jdbc, orders, new ObjectMapper());
        callback.recordDouyinNotification(channel, Map.of("out_order_no", "SHARE-1", "transaction_id", "REMOTE-TRADE",
                "state", "FINISHED", "receivers", List.of(Map.of("account", "receiver-account-01", "amount", 234, "result", "SUCCESS"))));
        records.recordResponse(channel, request, result(PaymentStatus.PENDING, Map.of("state", "PROCESSING")));
        records.recordResponse(channel, request, result(PaymentStatus.FAILED, Map.of("state", "FAILED")));
        assertThat(records.search(null, null, null, null, null).getFirst().status()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT raw_response FROM profit_sharing_record", String.class)).contains("FINISHED").doesNotContain("PROCESSING");
    }

    @Test
    void readOnlyQueriesRecoverMissingHistoryAndFillOnlyUnknownMetadataEvenAfterSuccess() {
        ProfitSharingQueryRequest request = new ProfitSharingQueryRequest(null, "REMOTE-TRADE", "SHARE-1", null, List.of("douyin"), Map.of());
        records.recordQuery(channel, request, result(PaymentStatus.SUCCESS, Map.of("out_order_no", "SHARE-1")));
        assertThat(records.search(null, null, null, null, null).getFirst().amount()).isNull();
        Map<String, Object> details = Map.of("out_order_no", "SHARE-1", "receivers", List.of(
                Map.of("account", "receiver-account-01", "amount", 123), Map.of("account", "receiver-account-02", "amount", 111)));
        records.recordQuery(channel, request, result(PaymentStatus.PENDING, details));
        var recovered = records.search(null, null, null, null, null).getFirst();
        assertThat(recovered.status()).isEqualTo("SUCCESS");
        assertThat(recovered.amount()).isEqualByComparingTo("2.34");
        assertThat(recovered.recipient()).contains("re***01", "re***02").doesNotContain("receiver-account");
        records.recordQuery(channel, request, result(PaymentStatus.SUCCESS, Map.of("receivers", List.of(Map.of("amount", 999)))));
        assertThat(records.search(null, null, null, null, null).getFirst().amount()).isEqualByComparingTo("2.34");
        assertThatThrownBy(() -> records.reserve(channel, request("SHARE-1", "2.34")))
                .isInstanceOf(ProfitSharingRecordException.class).hasMessageContaining("历史查单记录");
    }

    @Test
    void changedRequestCannotReplaceSavedRequestAndChannelIdentitiesStaySeparate() {
        records.reserve(channel, request("SHARE-1", "2.34"));
        assertThatThrownBy(() -> records.reserve(channel, request("SHARE-1", "9.99")))
                .isInstanceOf(ProfitSharingRecordException.class).hasMessageContaining("原参数不一致");
        PaymentGatewayProperties.Channel other = new PaymentGatewayProperties.Channel();
        other.setId("alipay"); other.setProvider("ALIPAY");
        records.reserve(other, request("SHARE-1", "9.99"));
        assertThat(records.search(null, null, "SHARE-1", null, null)).hasSize(2);
        assertThat(records.search(null, null, null, null, "douyin").getFirst().amount()).isEqualByComparingTo("2.34");
    }

    @Test
    void filtersBeforeReadingResultsWithoutArbitraryRecentLimit() {
        records.reserve(channel, request("OLD-MATCH", "2.34"));
        jdbc.update("UPDATE profit_sharing_record SET created_at='2025-01-01 12:30:00'");
        for (int i = 0; i < 205; i++) {
            jdbc.update("INSERT INTO profit_sharing_record(channel_id,out_request_no,provider,status) VALUES('douyin',?,'DOUYIN','SUCCESS')", "NEW-" + i);
        }
        assertThat(records.search("2025-01-01T12:00", "2025-01-01T13:00", "OLD", "REMOTE", "douyin"))
                .hasSize(1).allMatch(r -> r.orderNo().equals("OLD-MATCH"));
        assertThat(records.search(null, null, null, null, null)).hasSize(206);
    }

    @Test
    void localPaymentMetadataIsAssociatedWithoutChangingPaymentState() {
        orders.recordPaymentCreated("PAY-1", "REMOTE-TRADE", "douyin", "M1", "Merchant", "Douyin H5",
                new BigDecimal("50"), false, PaymentStatus.SUCCESS);
        records.reserve(channel, request("SHARE-1", "2.34"));
        var view = records.search(null, null, "PAY-1", null, null).getFirst();
        assertThat(view.relatedOutTradeNo()).isEqualTo("PAY-1");
        assertThat(view.merchantId()).isEqualTo("M1");
        assertThat(view.amount()).isEqualByComparingTo("2.34");
        assertThat(orders.view("PAY-1").amount()).isEqualByComparingTo("50");
        assertThat(orders.view("PAY-1").profitShared()).isFalse();
    }

    @Test
    void memoryModeAlsoRecordsAndKeepsSuccessMonotonic() {
        ProfitSharingRecordService memory = new ProfitSharingRecordService(orders);
        ProfitSharingRequest request = request("SHARE-1", "2.34");
        memory.reserve(channel, request);
        memory.recordResponse(channel, request, result(PaymentStatus.SUCCESS, Map.of()));
        memory.recordResponse(channel, request, result(PaymentStatus.PENDING, Map.of()));
        assertThat(memory.search(null, null, "SHARE", null, null)).hasSize(1).allMatch(r -> r.status().equals("SUCCESS"));
    }

    @Test
    void concurrentInstancesOnlyGrantOneSubmissionForTheSameRequest() throws Exception {
        ProfitSharingRecordService other = new ProfitSharingRecordService(jdbc, orders, new ObjectMapper());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reserveAfter(start, records));
            var second = executor.submit(() -> reserveAfter(start, other));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("SUBMIT", "PROFIT_SHARING_ALREADY_RECORDED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profit_sharing_record", Integer.class)).isEqualTo(1);
    }

    @Test
    void pendingAndFailedRecordsNeverGrantAnotherSubmission() {
        ProfitSharingRequest request = request("SHARE-1", "2.34");
        records.reserve(channel, request);
        assertThatThrownBy(() -> records.reserve(channel, request)).isInstanceOf(ProfitSharingRecordException.class)
                .hasMessageContaining("不会再次提交");
        records.recordResponse(channel, request, result(PaymentStatus.FAILED, Map.of()));
        assertThatThrownBy(() -> records.reserve(channel, request)).isInstanceOf(ProfitSharingRecordException.class)
                .hasMessageContaining("不会再次提交");
    }

    @Test
    void alipayEffectiveAuthorizationIsFingerprintedWithoutStoringTheToken() {
        channel.setProvider("ALIPAY");
        channel.getAlipay().setAppAuthToken("channel-secret");
        ProfitSharingRequest original = request("SHARE-1", "2.34");
        ProfitSharingRequest inherited = new ProfitSharingRequest(original.outTradeNo(), original.tradeNo(), original.outRequestNo(),
                original.royaltyParameters(), null, null, original.channelIds(), original.extra());
        records.reserve(channel, inherited);
        records.recordResponse(channel, inherited, result(PaymentStatus.SUCCESS, Map.of()));
        String audit = jdbc.queryForObject("SELECT raw_request FROM profit_sharing_record", String.class);
        assertThat(audit).contains("authorizationContext").doesNotContain("channel-secret");
        channel.getAlipay().setAppAuthToken("changed-channel-secret");
        assertThatThrownBy(() -> records.reserve(channel, inherited)).isInstanceOf(ProfitSharingRecordException.class)
                .hasMessageContaining("原参数不一致");
        ProfitSharingRequest explicitOriginal = new ProfitSharingRequest(original.outTradeNo(), original.tradeNo(), original.outRequestNo(),
                original.royaltyParameters(), null, "channel-secret", original.channelIds(), original.extra());
        assertThat(records.reserve(channel, explicitOriginal).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void existingTableWorksForRuntimeUserWithoutCreatePrivilege() {
        jdbc.execute("CREATE USER runtime_user PASSWORD 'test-only-password'");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE ON profit_sharing_record TO runtime_user");
        JdbcDataSource restricted = new JdbcDataSource();
        restricted.setURL(((JdbcDataSource) jdbc.getDataSource()).getURL().split(";")[0]);
        restricted.setUser("runtime_user");
        restricted.setPassword("test-only-password");
        JdbcTemplate runtime = new JdbcTemplate(restricted);
        assertThatThrownBy(() -> runtime.execute("CREATE TABLE unauthorized_table (id INT)"))
                .isInstanceOf(DataAccessException.class);

        ProfitSharingRecordService limited = new ProfitSharingRecordService(runtime, orders, new ObjectMapper());
        ProfitSharingRequest request = request("LIMITED-1", "2.34");
        limited.reserve(channel, request);
        limited.recordResponse(channel, request, result(PaymentStatus.SUCCESS, Map.of()));
        assertThat(limited.search(null, null, "LIMITED-1", null, null)).hasSize(1)
                .allMatch(record -> "SUCCESS".equals(record.status()));

        jdbc.execute("REVOKE SELECT ON profit_sharing_record FROM runtime_user");
        assertThatThrownBy(() -> new ProfitSharingRecordService(runtime, orders, new ObjectMapper()))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08001", "42000", "42S22"})
    void connectionPermissionAndSchemaErrorsNeverTriggerCreate(String sqlState) {
        JdbcTemplate unavailable = mock(JdbcTemplate.class);
        DataAccessException failure = new UncategorizedSQLException("probe", "SELECT", new SQLException("Database rejected probe", sqlState));
        doThrow(failure).when(unavailable).execute(anyString());
        assertThatThrownBy(() -> new ProfitSharingRecordService(unavailable, orders, new ObjectMapper())).isSameAs(failure);
        verify(unavailable, times(1)).execute(anyString());
    }

    private String reserveAfter(CountDownLatch start, ProfitSharingRecordService service) throws Exception {
        start.await();
        try {
            service.reserve(channel, request("SHARE-1", "2.34"));
            return "SUBMIT";
        } catch (ProfitSharingRecordException ex) { return ex.code(); }
    }

    private static ProfitSharingRequest request(String id, String amount) {
        return new ProfitSharingRequest(null, "REMOTE-TRADE", id,
                List.of(Map.of("trans_in_type", "PERSONAL_OPENID", "trans_in", "receiver-account-01", "amount", amount)),
                null, "secret-app-token", List.of("douyin"), Map.of());
    }

    private static GatewayResponse result(PaymentStatus status, Map<String, Object> raw) {
        return new GatewayResponse("douyin", status, "RESULT", "Result", null, "REMOTE-TRADE", null, null, raw, List.of());
    }
}
