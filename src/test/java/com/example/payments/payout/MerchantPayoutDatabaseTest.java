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
            assertThat(query.get(5, TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
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
        notifyResult(provider, "REPLAY-ORDER", "FAIL", "STALE-PLATFORM");
        notifyResult(provider, "REPLAY-ORDER", "PENDING", "STALE-PLATFORM");
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
        notifyResult(provider, "FAILED-ORDER", "PENDING", "STALE-PLATFORM");
        assertThat(service.search(null, null, "FAILED-ORDER", null, null).getFirst().status()).isEqualTo("FAILED");
        notifyResult(provider, "FAILED-ORDER", "SUCCESS", "SUCCESS-PLATFORM");
        assertThat(service.search(null, null, "FAILED-ORDER", null, null).getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    void mismatchedNotificationCannotAlterAnotherChannelsPayout() {
        seed("OWNED-ORDER", "ALIPAY", "2026-09-28 12:00:00", "PENDING", null, null);
        assertThatThrownBy(() -> service.recordDouyinNotification("DOUYIN", "OWNED-ORDER", "FOREIGN", "SUCCESS",
                new BigDecimal("12.34"), null, Map.of())).isInstanceOf(GatewayException.class);
        assertThat(service.search(null, null, "OWNED-ORDER", null, null).getFirst().status()).isEqualTo("PENDING");
    }

    private void prepareQueryResponse(String provider, String state) {
        if (provider.equals("ALIPAY")) {
            if (state.equals("UNKNOWN")) {
                when(alipay.execute(any(), anyString(), anyMap(), any())).thenThrow(new GatewayException("ALIPAY_REQUEST_ERROR", "late timeout"));
            } else {
                when(alipay.execute(any(), anyString(), anyMap(), any())).thenReturn(new AlipayGatewayResponse("query", "response", true,
                        "10000", "late", null, null, Map.of("status", "DEALING", "order_id", "LATE-PLATFORM"), Map.of("late", true)));
            }
        } else if (state.equals("UNKNOWN")) {
            when(douyin.get(any(), anyString())).thenThrow(new GatewayException("DOUYIN_REQUEST_ERROR", "late timeout"));
        } else {
            when(douyin.get(any(), anyString())).thenReturn(new DouyinGatewayResponse(200,
                    Map.of("state", "ACCEPTED", "transfer_bill_no", "LATE-PLATFORM", "message", "late"), "{}", Map.of()));
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
