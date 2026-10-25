package com.example.payments.order;

import com.example.payments.domain.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class PreauthCaptureSafetyTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unrelatedSuffixOrderCannotChangeAnotherMerchantOrder(boolean database) {
        Fixture f = new Fixture(database);
        f.create("ORDER", "original", "M1", "100", false);
        f.create("ORDER_PAY_1", "other", "M2", "0.01", false);
        DemoOrderView paid = f.first.recordAlipayNotify("ORDER_PAY_1", "CHILD-TRADE", "other", money("0.01"), "TRADE_SUCCESS", true);
        assertThat(paid.outTradeNo()).isEqualTo("ORDER_PAY_1");
        assertThat(paid.merchantId()).isEqualTo("M2");
        assertThat(f.second.view("ORDER").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(f.second.view("ORDER").channelId()).isEqualTo("original");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingCaptureReservesIdentityAndBalanceAndSurvivesAnotherServiceInstance(boolean database) {
        Fixture f = new Fixture(database);
        f.create("AUTH", "original", "M1", "100", true);
        assertThat(f.first.reservePreauthCapture(capture("CAPTURE", "3", "NOT_COMPLETE"), "original")).isNull();
        assertThat(f.second.captureOrderView("AUTH", "CAPTURE").amount()).isEqualByComparingTo("3");
        assertThat(f.second.captureOrderView("OTHER", "CAPTURE")).isNull();
        assertThat(f.second.reservePreauthCapture(capture("CAPTURE", "3", "NOT_COMPLETE"), "original").status())
                .isEqualTo(PaymentStatus.PENDING);
        assertThatThrownBy(() -> f.second.reservePreauthCapture(capture("CAPTURE", "4", "NOT_COMPLETE"), "original"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.second.reservePreauthCapture(capture("SECOND", "1", "NOT_COMPLETE"), "original"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.second.reservePreauthUnfreeze(unfreeze("RELEASE", "1")))
                .isInstanceOf(IllegalStateException.class);
        f.first.recordCaptureResult("CAPTURE", null, "original", PaymentStatus.PENDING, null, false);
        assertThat(f.second.view("AUTH").status()).isEqualTo(DemoOrderStatus.FROZEN);
        assertThat(f.second.view("CAPTURE").status()).isEqualTo(DemoOrderStatus.UNPAID);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialCaptureHasItsOwnRefundablePrincipalAndDoesNotLoseFrozenLedger(boolean database) {
        Fixture f = new Fixture(database);
        f.create("AUTH", "original", "M1", "100", true);
        f.first.reservePreauthCapture(capture("CAPTURE", "3", "NOT_COMPLETE"), "original");
        f.second.recordAlipayNotify("CAPTURE", "CAPTURE-TRADE", "original", money("3"), "TRADE_SUCCESS", true);
        f.first.recordCaptureResult("CAPTURE", null, "original", PaymentStatus.PENDING, null, false);
        f.first.recordAlipayNotify("CAPTURE", "CAPTURE-TRADE", "original", money("3"), "TRADE_SUCCESS", true);
        assertThat(f.second.view("CAPTURE").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(f.second.view("CAPTURE").refundableAmount()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> f.second.ensureRefundable("CAPTURE", "CAPTURE-TRADE", money("4")))
                .isInstanceOf(IllegalArgumentException.class);
        DemoOrderView parent = f.second.view("AUTH");
        assertThat(parent.amount()).isEqualByComparingTo("100");
        assertThat(parent.tradeNo()).isEqualTo("AUTH-NO");
        assertThat(parent.preAuthorization()).isTrue();
        assertThat(parent.preauthCapturedAmount()).isEqualByComparingTo("3");
        assertThat(parent.preauthUnfreezeRemainingAmount()).isEqualByComparingTo("97");
        assertThatThrownBy(() -> f.second.reservePreauthCapture(capture("TOO-MUCH", "98", "NOT_COMPLETE"), "original"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.second.delete("AUTH")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.second.delete("CAPTURE")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completeCaptureReleasesRemainderAndFreezeReplayCannotReopenIt(boolean database) {
        Fixture f = new Fixture(database);
        f.create("AUTH", "original", "M1", "100", true);
        f.first.reservePreauthCapture(capture("CAPTURE", "20", "COMPLETE"), "original");
        f.second.recordCaptureResult("CAPTURE", "TRADE", "original", PaymentStatus.SUCCESS, money("20"), true);
        f.first.recordAlipayNotify("AUTH", "AUTH-NO", "original", money("100"), "TRADE_SUCCESS", false);
        assertThat(f.second.view("AUTH").status()).isEqualTo(DemoOrderStatus.UNFROZEN);
        assertThat(f.second.view("AUTH").preauthUnfreezeRemainingAmount()).isEqualByComparingTo("0");
        assertThat(f.second.view("AUTH").preauthUnfrozenAmount()).isEqualByComparingTo("0");
        assertThat(f.second.view("AUTH").preauthCapturedAmount()).isEqualByComparingTo("20");
        assertThatThrownBy(() -> f.second.reservePreauthCapture(capture("AGAIN", "1", "COMPLETE"), "original"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wrongCaptureIdentityDoesNotWriteEitherOrder(boolean database) {
        Fixture f = new Fixture(database);
        f.create("AUTH", "original", "M1", "100", true);
        f.first.reservePreauthCapture(capture("CAPTURE", "3", "NOT_COMPLETE"), "original");
        assertThatThrownBy(() -> f.second.recordCaptureResult("CAPTURE", "TRADE", "other", PaymentStatus.SUCCESS, money("3"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.second.recordCaptureResult("CAPTURE", "TRADE", "original", PaymentStatus.SUCCESS, money("1"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.second.recordCaptureResult("CAPTURE", "TRADE", "original", PaymentStatus.SUCCESS, null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.second.view("CAPTURE").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(f.second.view("AUTH").preauthCapturedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void concurrentCaptureAndUnfreezeAcrossInstancesHaveOnlyOneReservation() throws Exception {
        Fixture f = new Fixture(true);
        f.create("AUTH", "original", "M1", "100", true);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var capture = executor.submit(() -> { start.await(); return attempt(() -> f.first.reservePreauthCapture(capture("CAPTURE", "80", "NOT_COMPLETE"), "original")); });
            var unfreeze = executor.submit(() -> { start.await(); return attempt(() -> f.second.reservePreauthUnfreeze(unfreeze("UNFREEZE", "80"))); });
            start.countDown();
            assertThat(List.of(capture.get(), unfreeze.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void captureInheritsMerchantNotificationTargetAndExplicitParent(boolean database) {
        Fixture f = new Fixture(database);
        f.create("AUTH", "original", "M1", "100", true);
        if (database) {
            f.jdbc.update("UPDATE pay_order SET notify_url = 'https://merchant.example/notify' WHERE out_trade_no = 'AUTH'");
        } else {
            f.first.recordPaymentMetadata("AUTH", new PayCreateRequest(PaymentProduct.ALIPAY_PREAUTH, "AUTH", "Freeze", money("100"),
                    null, null, null, null, null, "https://merchant.example/notify", null, null, null, null, Map.of(), null, null), null);
        }
        f.first.reservePreauthCapture(capture("CAPTURE", "3", "NOT_COMPLETE"), "original");
        assertThat(f.second.merchantNotifyTarget("CAPTURE").orElseThrow().notifyUrl()).isEqualTo("https://merchant.example/notify");
        assertThat(f.second.merchantNotifyTarget("CAPTURE").orElseThrow().merchantId()).isEqualTo("M1");
        assertThat(f.second.captureParentOutTradeNo("CAPTURE")).isEqualTo("AUTH");
        assertThat(f.second.captureParentOutTradeNo("AUTH_PAY_1")).isNull();
    }

    @Test
    void newSynchronousReceiptHasPaidAtButHistoricalReplayKeepsUnknownTime() {
        Fixture f = new Fixture(true);
        f.first.recordPaymentCreated("PAID", "TRADE", "original", "M1", "Merchant", "Payment", money("3"), false, PaymentStatus.SUCCESS);
        assertThat(f.jdbc.queryForObject("SELECT paid_at FROM pay_order WHERE out_trade_no = 'PAID'", java.sql.Timestamp.class)).isNotNull();
        f.jdbc.update("UPDATE pay_order SET paid_at = NULL WHERE out_trade_no = 'PAID'");
        f.second.recordAlipayNotify("PAID", "TRADE", "original", money("3"), "TRADE_SUCCESS", true);
        f.second.markProfitShared("PAID");
        assertThat(f.jdbc.queryForObject("SELECT paid_at FROM pay_order WHERE out_trade_no = 'PAID'", java.sql.Timestamp.class)).isNull();
    }

    @Test
    void concurrentOriginalFreezeReplayCannotReopenCompletedAuthorization() throws Exception {
        Fixture f = new Fixture(true);
        f.create("AUTH", "original", "M1", "100", true);
        f.first.reservePreauthCapture(capture("CAPTURE", "20", "COMPLETE"), "original");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var completion = executor.submit(() -> { start.await(); return f.first.recordCaptureResult("CAPTURE", "TRADE", "original", PaymentStatus.SUCCESS, money("20"), true); });
            var replay = executor.submit(() -> { start.await(); return f.second.recordAlipayNotify("AUTH", "AUTH-NO", "original", money("100"), "TRADE_SUCCESS", false); });
            start.countDown();
            completion.get();
            replay.get();
        }
        assertThat(f.first.view("AUTH").status()).isEqualTo(DemoOrderStatus.UNFROZEN);
        assertThat(f.first.view("AUTH").preauthUnfreezeRemainingAmount()).isEqualByComparingTo("0");
    }

    private static boolean attempt(Runnable action) {
        try { action.run(); return true; } catch (IllegalStateException ex) { return false; }
    }
    private static PreauthCaptureRequest capture(String child, String amount, String mode) {
        return new PreauthCaptureRequest("AUTH", child, "AUTH-NO", "Capture", money(amount), null, null, mode,
                null, List.of("original"), Map.of());
    }
    private static PreauthUnfreezeRequest unfreeze(String request, String amount) {
        return new PreauthUnfreezeRequest("AUTH", "AUTH-NO", request, money(amount), null, null, List.of("original"), Map.of());
    }
    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static final class Fixture {
        final DemoOrderService first;
        final DemoOrderService second;
        final JdbcTemplate jdbc;
        Fixture(boolean database) {
            if (!database) { first = second = new DemoOrderService(); jdbc = null; return; }
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE merchant (merchant_id VARCHAR(96) PRIMARY KEY, name VARCHAR(255))");
            jdbc.execute("CREATE TABLE pay_channel (id VARCHAR(64) PRIMARY KEY)");
            jdbc.execute("""
                    CREATE TABLE pay_order (
                      out_trade_no VARCHAR(96) PRIMARY KEY, trade_no VARCHAR(128), merchant_id VARCHAR(96), channel_id VARCHAR(64),
                      product VARCHAR(128), subject VARCHAR(255), amount DECIMAL(18,2), status VARCHAR(32),
                      pre_authorization BOOLEAN, supplemented BOOLEAN, profit_shared BOOLEAN, created_at TIMESTAMP,
                      paid_at TIMESTAMP, frozen_at TIMESTAMP, refunded_at TIMESTAMP, closed_at TIMESTAMP, notify_url VARCHAR(2048)
                    )
                    """);
            jdbc.execute("CREATE TABLE refund_order (out_trade_no VARCHAR(96), refund_amount DECIMAL(18,2), status VARCHAR(32))");
            jdbc.execute("""
                    CREATE TABLE preauth_unfreeze_order (
                      out_request_no VARCHAR(96) PRIMARY KEY, out_trade_no VARCHAR(96), auth_no VARCHAR(128),
                      channel_id VARCHAR(64), amount DECIMAL(18,2), status VARCHAR(32), remark VARCHAR(255),
                      code VARCHAR(64), message VARCHAR(512), raw_response CLOB, completed_at TIMESTAMP
                    )
                    """);
            jdbc.update("INSERT INTO merchant VALUES ('M1', 'First'), ('M2', 'Second')");
            jdbc.update("INSERT INTO pay_channel VALUES ('original'), ('other')");
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("jdbc", jdbc);
            first = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
            second = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
        }
        void create(String number, String channel, String merchant, String amount, boolean preauth) {
            first.recordPaymentCreated(number, preauth ? "AUTH-NO" : null, channel, merchant, "Merchant", "Product",
                    money(amount), preauth, preauth ? PaymentStatus.SUCCESS : PaymentStatus.CREATED);
        }
    }
}
