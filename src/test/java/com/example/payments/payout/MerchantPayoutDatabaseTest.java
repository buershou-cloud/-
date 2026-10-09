package com.example.payments.payout;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.alipay.AlipayGatewayResponse;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.gateway.douyin.DouyinGatewayResponse;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MerchantPayoutDatabaseTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private MerchantPayoutService service;
    private final ChannelRegistry registry = mock(ChannelRegistry.class);
    private final AlipayOpenApiClient alipay = mock(AlipayOpenApiClient.class);
    private final DouyinPayClient douyin = mock(DouyinPayClient.class);

    @BeforeEach
    void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:payout-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        // H2 lacks MySQL DATE_FORMAT; only this display-format function is adapted for the test.
        jdbc.execute("CREATE ALIAS DATE_FORMAT FOR 'com.example.payments.payout.MerchantPayoutDatabaseTest.mysqlDateFormat'");
        jdbc.execute("""
                CREATE TABLE merchant_payout (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, out_biz_no VARCHAR(32) NOT NULL UNIQUE,
                    provider VARCHAR(32), channel_id VARCHAR(64), recipient_type VARCHAR(32),
                    recipient_masked VARCHAR(255), recipient_name_masked VARCHAR(128), amount DECIMAL(18,2),
                    order_title VARCHAR(64), remark VARCHAR(200), transfer_scene_id VARCHAR(64),
                    platform_order_no VARCHAR(128), platform_fund_order_no VARCHAR(128),
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING', code VARCHAR(64), message VARCHAR(512),
                    fail_reason VARCHAR(512), raw_request CLOB, raw_response CLOB,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMP
                )
                """);
        for (String provider : List.of("ALIPAY", "DOUYIN")) {
            PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
            channel.setId(provider); channel.setProvider(provider);
            when(registry.find(provider)).thenReturn(Optional.of(channel));
            when(registry.isEnabled(channel)).thenReturn(true);
        }
        service = service(jdbc);
    }

    @Test
    void historicalSearchIsNotTruncatedByThePayoutPageLimit() {
        seed("HISTORICAL", "ALIPAY", "2025-01-01 10:00:00", "SUCCESS", "OLD-PLATFORM", "OLD-FUND");
        for (int index = 0; index < 600; index++) {
            seed("RECENT-" + index, "DOUYIN", "2026-09-01 10:00:00", "SUCCESS", "PLATFORM-" + index, null);
        }
        assertThat(service.list(200)).hasSize(200).noneMatch(row -> row.outBizNo().equals("HISTORICAL"));
        assertThat(service.list(999)).hasSize(500);
        assertThat(service.search(null, null, "HISTORICAL", null, null)).extracting(MerchantPayoutView::outBizNo)
                .containsExactly("HISTORICAL");
        assertThat(service.search(null, null, null, null, null)).hasSize(601);
        verifyNoInteractions(alipay, douyin);
    }

    @Test
    void searchCombinesTimeOrderChannelAndEitherPlatformIdentifierInSql() {
        seed("ORDER-ALIPAY", "ALIPAY", "2026-09-28 12:00:00", "SUCCESS", "PLATFORM-ALI", "FUND-ALI");
        seed("ORDER-DOUYIN", "DOUYIN", "2026-09-28 12:00:00", "SUCCESS", "PLATFORM-DY", null);
        seed("ORDER-OLDER", "ALIPAY", "2026-09-27 12:00:00", "SUCCESS", "PLATFORM-OLD", "FUND-OLD");
        assertThat(service.search("2026-09-28T11:59", "2026-09-28T12:01", "ORDER", "FUND-ALI", "ALIPAY"))
                .extracting(MerchantPayoutView::outBizNo).containsExactly("ORDER-ALIPAY");
        assertThat(service.search(null, null, null, "PLATFORM-DY", null))
                .extracting(MerchantPayoutView::outBizNo).containsExactly("ORDER-DOUYIN");
        assertThat(service.search(null, null, "' OR 1=1 --", null, null)).isEmpty();
        assertThat(service.search(null, null, null, "FUND-ALI", "DOUYIN")).isEmpty();
        MerchantPayoutView masked = service.search(null, null, "ORDER-ALIPAY", null, null).getFirst();
        assertThat(masked.amount()).isEqualByComparingTo("12.34");
        assertThat(masked.recipientMasked()).isEqualTo("138****1234");
        assertThat(masked.recipientNameMasked()).isEqualTo("张**");
        verifyNoInteractions(alipay, douyin);
    }

    @ParameterizedTest
    @CsvSource({"ALIPAY,PROCESSING", "DOUYIN,PROCESSING", "ALIPAY,UNKNOWN", "DOUYIN,UNKNOWN"})
    void successCallbackWinsOverAQueryUpdateAlreadyWaitingToExecute(String provider, String lateStatus) throws Exception {
        seed("RACE-ORDER", provider, "2026-09-28 12:00:00", "PENDING", null, null);
        BlockingJdbcTemplate lateJdbc = new BlockingJdbcTemplate(dataSource, lateStatus);
        MerchantPayoutService lateService = service(lateJdbc);
        prepareQueryResponse(provider, lateStatus);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var query = executor.submit(() -> lateService.query("RACE-ORDER"));
            try {
                assertThat(lateJdbc.updateReady.await(5, TimeUnit.SECONDS)).isTrue();
                notifyResult(provider, "RACE-ORDER", "SUCCESS", "SUCCESS-PLATFORM");
            } finally {
                lateJdbc.continueUpdate.countDown();
            }
            assertThatThrownBy(() -> query.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(GatewayException.class).hasMessageContaining("本次原单查询未能确认结果");
        }
        MerchantPayoutView persisted = service.search(null, null, "RACE-ORDER", null, null).getFirst();
        assertThat(persisted.status()).isEqualTo("SUCCESS");
        assertThat(persisted.platformOrderNo()).isEqualTo("SUCCESS-PLATFORM");
        assertThat(persisted.message()).isEqualTo("SUCCESS");
        assertThat(persisted.failReason()).isNull();
        assertThat(jdbc.queryForObject("SELECT raw_response FROM merchant_payout WHERE out_biz_no = 'RACE-ORDER'", String.class))
                .contains("confirmed").doesNotContain("late");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY", "DOUYIN"})
    void replayAndLateFailureCannotDowngradeConfirmedSuccess(String provider) {
        seed("REPLAY-ORDER", provider, "2026-09-28 12:00:00", "PENDING", null, null);
        notifyResult(provider, "REPLAY-ORDER", "SUCCESS", "SUCCESS-PLATFORM");
        Timestamp completed = jdbc.queryForObject("SELECT completed_at FROM merchant_payout WHERE out_biz_no = 'REPLAY-ORDER'", Timestamp.class);
        notifyResult(provider, "REPLAY-ORDER", "SUCCESS", "SUCCESS-PLATFORM");
        notifyResult(provider, "REPLAY-ORDER", "FAIL", "SUCCESS-PLATFORM");
        notifyResult(provider, "REPLAY-ORDER", "PENDING", "SUCCESS-PLATFORM");
        MerchantPayoutView row = service.search(null, null, "REPLAY-ORDER", null, null).getFirst();
        assertThat(row.status()).isEqualTo("SUCCESS");
        assertThat(row.platformOrderNo()).isEqualTo("SUCCESS-PLATFORM");
        assertThat(row.message()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM merchant_payout WHERE out_biz_no = 'REPLAY-ORDER'", Timestamp.class))
                .isEqualTo(completed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY", "DOUYIN"})
    void failureOnlyAdvancesWhenSuccessIsConfirmed(String provider) {
        seed("FAILED-ORDER", provider, "2026-09-28 12:00:00", "PENDING", null, null);
        notifyResult(provider, "FAILED-ORDER", "FAIL", "FAILED-PLATFORM");
        notifyResult(provider, "FAILED-ORDER", "PENDING", "FAILED-PLATFORM");
        assertThat(service.search(null, null, "FAILED-ORDER", null, null).getFirst().status()).isEqualTo("FAILED");
        notifyResult(provider, "FAILED-ORDER", "SUCCESS", "FAILED-PLATFORM");
        assertThat(service.search(null, null, "FAILED-ORDER", null, null).getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    void mismatchedNotificationCannotAlterAnotherChannelsPayout() {
        seed("OWNED-ORDER", "ALIPAY", "2026-09-28 12:00:00", "PENDING", null, null);
        assertThatThrownBy(() -> service.recordDouyinNotification("DOUYIN", "OWNED-ORDER", "FOREIGN", "SUCCESS",
                new BigDecimal("12.34"), null, Map.of())).isInstanceOf(GatewayException.class);
        assertThat(service.search(null, null, "OWNED-ORDER", null, null).getFirst().status()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "UNKNOWN", "SUCCESS", "FAILED"})
    void alipayQueryBusinessErrorReportsThisAttemptWithoutClaimingTransferFailed(String status) {
        seed("QUERY-ERROR", "ALIPAY", "2026-10-09 12:00:00", status, "ALI-ORDER", "ALI-FUND");
        jdbc.update("UPDATE merchant_payout SET message = 'previous result', raw_response = 'previous response' WHERE out_biz_no = 'QUERY-ERROR'");
        when(alipay.execute(any(), eq("alipay.fund.trans.common.query"), anyMap(), any()))
                .thenReturn(alipayResponse(true, false, "error_response", Map.of(), "40004", "QUERY_DENIED", "查询权限不足"));

        assertQueryUnconfirmed("QUERY-ERROR", "查询权限不足");

        MerchantPayoutView saved = row("QUERY-ERROR");
        boolean terminal = List.of("SUCCESS", "FAILED").contains(status);
        assertThat(saved.status()).isEqualTo(terminal ? status : "UNKNOWN");
        assertThat(saved.platformOrderNo()).isEqualTo("ALI-ORDER");
        assertThat(saved.platformFundOrderNo()).isEqualTo("ALI-FUND");
        assertThat(saved.completedAt()).isNull();
        if (terminal) {
            assertThat(saved.message()).isEqualTo("previous result");
            assertThat(jdbc.queryForObject("SELECT raw_response FROM merchant_payout WHERE out_biz_no = 'QUERY-ERROR'", String.class))
                    .isEqualTo("previous response");
        }
        verify(alipay, times(1)).execute(any(), eq("alipay.fund.trans.common.query"), anyMap(), any());
    }

    @ParameterizedTest
    @CsvSource({"ALIPAY,MISSING", "ALIPAY,UNRECOGNIZED", "DOUYIN,MISSING", "DOUYIN,UNRECOGNIZED"})
    void missingOrUnrecognizedQueryStatusIsUnknownAndVisible(String provider, String scenario) {
        seed("NO-STATE", provider, "2026-10-09 12:00:00", "PROCESSING", null, null);
        Map<String, Object> body = scenario.equals("MISSING") ? Map.of()
                : Map.of(provider.equals("ALIPAY") ? "status" : "state", "UNRECOGNIZED");
        prepareSuccessfulQuery(provider, body);

        assertQueryUnconfirmed("NO-STATE", "有效转账状态");
        assertThat(row("NO-STATE").status()).isEqualTo("UNKNOWN");
        assertThat(row("NO-STATE").completedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"DEALING,PROCESSING", "SUCCESS,SUCCESS", "FAIL,FAILED"})
    void confirmedAlipayQueryMapsStateAndKeepsTheTwoDifferentPlatformIdTypes(String state, String expected) {
        seed("VALID-QUERY", "ALIPAY", "2026-10-09 12:00:00", "PROCESSING", "ALI-ORDER", "ALI-FUND");
        prepareSuccessfulQuery("ALIPAY", Map.of("out_biz_no", "VALID-QUERY", "trans_amount", "12.34",
                "status", state, "order_id", "ALI-ORDER", "pay_fund_order_id", "ALI-FUND"));
        MerchantPayoutView result = service.query("VALID-QUERY");
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.platformOrderNo()).isEqualTo("ALI-ORDER");
        assertThat(result.platformFundOrderNo()).isEqualTo("ALI-FUND");
        verify(alipay).execute(any(), eq("alipay.fund.trans.common.query"),
                argThat(body -> body.get("out_biz_no").equals("VALID-QUERY")), any());
    }

    @ParameterizedTest
    @CsvSource({"ALIPAY,FAILED,DEALING", "ALIPAY,SUCCESS,FAIL", "DOUYIN,FAILED,PROCESSING", "DOUYIN,SUCCESS,FAIL"})
    void staleQueryCannotDescribeThePreviouslyConfirmedStateAsThisQueriesResult(String provider, String saved, String incoming) {
        seed("STATE-CONFLICT", provider, "2026-10-09 12:00:00", saved, "CONFIRMED-PLATFORM", null);
        prepareSuccessfulQuery(provider, Map.of(provider.equals("ALIPAY") ? "status" : "state", incoming));
        assertQueryUnconfirmed("STATE-CONFLICT", "本次返回状态");
        assertThat(row("STATE-CONFLICT").status()).isEqualTo(saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY", "DOUYIN"})
    void aQueryWithConfirmedSuccessCanUpgradePreviousFailure(String provider) {
        seed("CORRECTED-FAIL", provider, "2026-10-09 12:00:00", "FAILED", "CONFIRMED-PLATFORM", null);
        prepareSuccessfulQuery(provider, Map.of(provider.equals("ALIPAY") ? "status" : "state", "SUCCESS"));
        assertThat(service.query("CORRECTED-FAIL").status()).isEqualTo("SUCCESS");
    }

    @ParameterizedTest
    @CsvSource({"ALIPAY,out_biz_no,FOREIGN", "ALIPAY,trans_amount,1.00", "ALIPAY,amount,1.00",
            "ALIPAY,order_id,FOREIGN", "ALIPAY,pay_fund_order_id,FOREIGN", "ALIPAY,trans_amount,bad",
            "DOUYIN,out_bill_no,FOREIGN", "DOUYIN,transfer_amount,1", "DOUYIN,transfer_bill_no,FOREIGN"})
    void conflictingQueryEvidenceCannotChangeAnAlreadyConfirmedPayout(String provider, String key, String value) {
        seed("CONFLICT-QUERY", provider, "2026-10-09 12:00:00", "SUCCESS", "ORIGINAL-PLATFORM", "ORIGINAL-FUND");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put(provider.equals("ALIPAY") ? "status" : "state", "SUCCESS");
        body.put(key, value);
        prepareSuccessfulQuery(provider, body);
        assertQueryUnconfirmed("CONFLICT-QUERY", "代付回包");
        assertThat(row("CONFLICT-QUERY").status()).isEqualTo("SUCCESS");
        assertThat(row("CONFLICT-QUERY").platformOrderNo()).isEqualTo("ORIGINAL-PLATFORM");
        assertThat(row("CONFLICT-QUERY").platformFundOrderNo()).isEqualTo("ORIGINAL-FUND");
    }

    @Test
    void wrongAlipayResponseEnvelopeCannotConfirmAnotherMethodsResult() {
        seed("WRONG-METHOD", "ALIPAY", "2026-10-09 12:00:00", "PROCESSING", null, null);
        when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(alipayResponse(true, true,
                "alipay_trade_query_response", Map.of("status", "SUCCESS"), "10000", null, null));
        assertQueryUnconfirmed("WRONG-METHOD", "对应接口");
        assertThat(row("WRONG-METHOD").status()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY_PARSE_ERROR", "ALIPAY_HTTP_502", "ALIPAY_REQUEST_ERROR"})
    void unconfirmedCreateResponseIsUnknownAndCannotBeSubmittedAgain(String code) {
        registry.find("ALIPAY").orElseThrow().getAlipay().setCredentialMode("CERTIFICATE");
        when(alipay.execute(any(), eq("alipay.fund.trans.uni.transfer"), anyMap(), any()))
                .thenThrow(new GatewayException(code, "response unavailable"));
        MerchantPayoutCreateRequest request = alipayCreate("CREATE-UNKNOWN");
        assertThat(service.create(request, null).status()).isEqualTo("UNKNOWN");
        assertThat(row("CREATE-UNKNOWN").completedAt()).isNull();
        assertThatThrownBy(() -> service.create(request, null)).isInstanceOf(IllegalArgumentException.class);
        verify(alipay, times(1)).execute(any(), eq("alipay.fund.trans.uni.transfer"), anyMap(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-envelope", "missing-state", "system-error"})
    void damagedOrUnconfirmedAlipayCreateReplyDoesNotClaimFailure(String scenario) {
        registry.find("ALIPAY").orElseThrow().getAlipay().setCredentialMode("CERTIFICATE");
        AlipayGatewayResponse response = switch (scenario) {
            case "missing-envelope" -> alipayResponse(false, false, null, Map.of(), null, null, null);
            case "missing-state" -> alipayResponse(false, true, "alipay_fund_trans_uni_transfer_response", Map.of(), "10000", null, null);
            default -> alipayResponse(false, false, "error_response", Map.of(), "20000", "SYSTEM_ERROR", "系统繁忙");
        };
        when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(response);
        assertThat(service.create(alipayCreate("CREATE-INCOMPLETE"), null).status()).isEqualTo("UNKNOWN");
        assertThat(row("CREATE-INCOMPLETE").completedAt()).isNull();
    }

    @Test
    void explicitAlipayCreateRejectionInOfficialErrorEnvelopeRemainsFailure() {
        registry.find("ALIPAY").orElseThrow().getAlipay().setCredentialMode("CERTIFICATE");
        when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(alipayResponse(false, false,
                "error_response", Map.of(), "40004", "BUSINESS_LIMITED", "业务规则拒绝"));
        assertThat(service.create(alipayCreate("CREATE-REJECTED"), null).status()).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void alipayTransferFailurePreservesBusinessReasonInsteadOfApiSuccessMessage(boolean query) {
        String order = "ALIPAY-FAILURE";
        registry.find("ALIPAY").orElseThrow().getAlipay().setCredentialMode("CERTIFICATE");
        if (query) seed(order, "ALIPAY", "2026-10-09 12:00:00", "PROCESSING", null, null);
        Map<String, Object> body = Map.of("status", "FAIL", "error_code", "PAYEE_STATUS_ERROR", "fail_reason", "收款账户状态异常");
        when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(alipayResponse(query, true,
                query ? "alipay_fund_trans_common_query_response" : "alipay_fund_trans_uni_transfer_response",
                body, "10000", null, "Success"));
        MerchantPayoutView result = query ? service.query(order) : service.create(alipayCreate(order), null);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.code()).isEqualTo("10000");
        assertThat(result.failReason()).contains("PAYEE_STATUS_ERROR", "收款账户状态异常");
        assertThat(result.message()).contains("PAYEE_STATUS_ERROR", "收款账户状态异常").doesNotContain("Success");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyOptionalAlipayResponseFieldsDoNotInvalidateConfirmedSuccess(boolean query) {
        String order = "ALIPAY-OPTIONAL";
        registry.find("ALIPAY").orElseThrow().getAlipay().setCredentialMode("CERTIFICATE");
        if (query) seed(order, "ALIPAY", "2026-10-09 12:00:00", "PROCESSING", null, null);
        Map<String, Object> body = Map.of("status", "SUCCESS", "amount", "", "trans_amount", " ",
                "out_biz_no", "", "order_id", "", "pay_fund_order_id", " ");
        when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(alipayResponse(query, true,
                query ? "alipay_fund_trans_common_query_response" : "alipay_fund_trans_uni_transfer_response",
                body, "10000", null, "Success"));
        MerchantPayoutView result = query ? service.query(order) : service.create(alipayCreate(order), null);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.platformOrderNo()).isNull();
        assertThat(result.platformFundOrderNo()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY", "DOUYIN"})
    void conflictingSuccessfulQueryCannotReplaceAConcurrentCallbackPlatformId(String provider) throws Exception {
        seed("ID-RACE", provider, "2026-10-09 12:00:00", "PENDING", null, null);
        BlockingJdbcTemplate lateJdbc = new BlockingJdbcTemplate(dataSource, "SUCCESS");
        MerchantPayoutService lateService = service(lateJdbc);
        prepareSuccessfulQuery(provider, Map.of(provider.equals("ALIPAY") ? "status" : "state", "SUCCESS",
                provider.equals("ALIPAY") ? "order_id" : "transfer_bill_no", "FOREIGN-PLATFORM"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var query = executor.submit(() -> lateService.query("ID-RACE"));
            try {
                assertThat(lateJdbc.updateReady.await(5, TimeUnit.SECONDS)).isTrue();
                notifyResult(provider, "ID-RACE", "SUCCESS", "CONFIRMED-PLATFORM");
            } finally { lateJdbc.continueUpdate.countDown(); }
            assertThatThrownBy(() -> query.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(GatewayException.class).hasMessageContaining("平台单号");
        }
        assertThat(row("ID-RACE").status()).isEqualTo("SUCCESS");
        assertThat(row("ID-RACE").platformOrderNo()).isEqualTo("CONFIRMED-PLATFORM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY", "DOUYIN"})
    void conflictingSuccessNotificationCannotChangeStoredPlatformId(String provider) {
        seed("ID-REPLAY", provider, "2026-10-09 12:00:00", "SUCCESS", "CONFIRMED-PLATFORM", null);
        assertThatThrownBy(() -> notifyResult(provider, "ID-REPLAY", "SUCCESS", "FOREIGN-PLATFORM"))
                .isInstanceOf(GatewayException.class).hasMessageContaining("平台单号");
        assertThat(row("ID-REPLAY").platformOrderNo()).isEqualTo("CONFIRMED-PLATFORM");
    }

    private MerchantPayoutView row(String order) {
        return service.search(null, null, order, null, null).getFirst();
    }

    private void assertQueryUnconfirmed(String order, String message) {
        assertThatThrownBy(() -> service.query(order)).isInstanceOfSatisfying(GatewayException.class,
                error -> assertThat(error.code()).isEqualTo("PAYOUT_QUERY_UNCONFIRMED"))
                .hasMessageContaining(message).hasMessageContaining("请勿重复代付");
    }

    private void prepareSuccessfulQuery(String provider, Map<String, Object> body) {
        if (provider.equals("ALIPAY")) {
            when(alipay.execute(any(), eq("alipay.fund.trans.common.query"), anyMap(), any()))
                    .thenReturn(alipayResponse(true, true, "alipay_fund_trans_common_query_response", body, "10000", null, null));
        } else {
            when(douyin.get(any(), anyString())).thenReturn(new DouyinGatewayResponse(200, body, "{}", Map.of()));
        }
    }

    private static AlipayGatewayResponse alipayResponse(boolean query, boolean success, String key,
                                                        Map<String, Object> body, String code, String subCode, String message) {
        return new AlipayGatewayResponse(query ? "alipay.fund.trans.common.query" : "alipay.fund.trans.uni.transfer",
                key, success, code, message, subCode, message, body, Map.of("test-response", body));
    }

    private static MerchantPayoutCreateRequest alipayCreate(String order) {
        return new MerchantPayoutCreateRequest("ALIPAY", order, new BigDecimal("12.34"), "ALIPAY_USER_ID",
                "2088000000000000", null, "代付", "备注", null, null, null, "unused-mock-password");
    }

    private void prepareQueryResponse(String provider, String state) {
        if (provider.equals("ALIPAY")) {
            if (state.equals("UNKNOWN")) {
                when(alipay.execute(any(), anyString(), anyMap(), any())).thenThrow(new GatewayException("ALIPAY_REQUEST_ERROR", "late timeout"));
            } else {
                when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(new AlipayGatewayResponse("alipay.fund.trans.common.query", "alipay_fund_trans_common_query_response", true,
                        "10000", "late", null, null, Map.of("status", "DEALING", "order_id", "SUCCESS-PLATFORM"), Map.of("late", true)));
            }
        } else if (state.equals("UNKNOWN")) {
            when(douyin.get(any(), anyString())).thenThrow(new GatewayException("DOUYIN_REQUEST_ERROR", "late timeout"));
        } else {
            when(douyin.get(any(), anyString())).thenReturn(new DouyinGatewayResponse(200,
                    Map.of("state", "ACCEPTED", "transfer_bill_no", "SUCCESS-PLATFORM", "message", "late"), "{}", Map.of()));
        }
    }

    private void notifyResult(String provider, String order, String status, String platform) {
        Map<String, Object> raw = Map.of("confirmed", status);
        if (provider.equals("ALIPAY")) {
            service.recordAlipayNotification(provider, order, platform, null, status, new BigDecimal("12.34"), raw);
        } else {
            service.recordDouyinNotification(provider, order, platform, status, new BigDecimal("12.34"), null, raw);
        }
    }

    private MerchantPayoutService service(JdbcTemplate template) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jdbcTemplate", template);
        return new MerchantPayoutService(beans.getBeanProvider(JdbcTemplate.class), registry, alipay, douyin, new ObjectMapper());
    }

    private void seed(String order, String provider, String created, String status, String platform, String fund) {
        jdbc.update("""
                INSERT INTO merchant_payout (out_biz_no, provider, channel_id, recipient_type, recipient_masked,
                    recipient_name_masked, amount, order_title, status, platform_order_no, platform_fund_order_no, created_at)
                VALUES (?, ?, ?, ?, '138****1234', '张**', 12.34, '代付', ?, ?, ?, ?)
                """, order, provider, provider, provider.equals("ALIPAY") ? "ALIPAY_USER_ID" : "DOUYIN_OPEN_ID",
                status, platform, fund, Timestamp.valueOf(created));
    }

    public static String mysqlDateFormat(Timestamp value, String pattern) {
        if (!"%Y-%m-%d %H:%i:%s".equals(pattern)) throw new IllegalArgumentException("Unexpected test date format");
        return value == null ? null : value.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static final class BlockingJdbcTemplate extends JdbcTemplate {
        private final String blockedStatus;
        final CountDownLatch updateReady = new CountDownLatch(1);
        final CountDownLatch continueUpdate = new CountDownLatch(1);

        private BlockingJdbcTemplate(JdbcDataSource dataSource, String blockedStatus) {
            super(dataSource);
            this.blockedStatus = blockedStatus;
        }

        @Override
        public int update(String sql, Object... arguments) {
            if (sql.contains("UPDATE merchant_payout") && arguments.length > 2 && blockedStatus.equals(arguments[2])) {
                updateReady.countDown();
                try {
                    if (!continueUpdate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out coordinating database race test");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return super.update(sql, arguments);
        }
    }
}
