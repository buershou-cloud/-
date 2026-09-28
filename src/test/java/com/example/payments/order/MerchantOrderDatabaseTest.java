package com.example.payments.order;

import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.merchant.api.MerchantApiException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** Real SQL/transaction tests: independent service instances share a database, not a Java monitor. */
class MerchantOrderDatabaseTest {
    private JdbcTemplate jdbc;
    private DemoOrderService first;
    private DemoOrderService second;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE merchant (merchant_id VARCHAR(96) PRIMARY KEY, name VARCHAR(255))");
        jdbc.execute("CREATE TABLE pay_channel (id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE pay_order (
                    out_trade_no VARCHAR(96) PRIMARY KEY, trade_no VARCHAR(128), channel_id VARCHAR(64),
                    merchant_id VARCHAR(96), product VARCHAR(128), subject VARCHAR(255), amount DECIMAL(18,2),
                    status VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    pre_authorization BOOLEAN DEFAULT FALSE, supplemented BOOLEAN DEFAULT FALSE, profit_shared BOOLEAN DEFAULT FALSE,
                    buyer_id VARCHAR(128), buyer_open_id VARCHAR(128), auth_code VARCHAR(128), notify_url VARCHAR(2048),
                    return_url VARCHAR(2048), app_auth_token VARCHAR(512), settle_info CLOB, royalty_info CLOB,
                    extra CLOB, raw_request CLOB, raw_response CLOB,
                    paid_at TIMESTAMP, frozen_at TIMESTAMP, refunded_at TIMESTAMP, closed_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE refund_order (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, out_request_no VARCHAR(96) NOT NULL UNIQUE,
                    out_trade_no VARCHAR(96) NOT NULL, trade_no VARCHAR(128), merchant_id VARCHAR(96), channel_id VARCHAR(64),
                    refund_amount DECIMAL(18,2), status VARCHAR(32), refund_reason VARCHAR(255),
                    code VARCHAR(128), message VARCHAR(2048), raw_response CLOB, completed_at TIMESTAMP
                )
                """);
        jdbc.execute("CREATE TABLE preauth_unfreeze_order (out_trade_no VARCHAR(96), amount DECIMAL(18,2), status VARCHAR(32))");
        jdbc.update("INSERT INTO merchant (merchant_id, name) VALUES ('M1', 'First'), ('M2', 'Second')");
        jdbc.update("INSERT INTO pay_channel (id) VALUES ('original'), ('other')");
        seed("ONE", "T1", "M1");
        seed("TWO", "T2", "M2");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jdbcTemplate", jdbc);
        first = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
        second = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
    }

    @Test
    void concurrentRefundsAcrossInstancesCannotReserveMoreThanOrderBalance() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> requestOne = () -> reserve(first, "M1", refund("ONE", "T1", "R1", "7"), start);
        Callable<String> requestTwo = () -> reserve(second, "M1", refund("ONE", "T1", "R2", "7"), start);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(requestOne);
            var two = executor.submit(requestTwo);
            start.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder("OK", "REFUND_PENDING");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(refund_amount) FROM refund_order WHERE status = 'PENDING'", BigDecimal.class))
                .isEqualByComparingTo("7");
    }

    @Test
    void confirmedRefundIsCountedOnceAndOnlyOutstandingPendingAmountIsReserved() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "DONE", "4"));
        // Simulate the existing verified upstream refund reconciliation writing its confirmed result.
        jdbc.update("UPDATE refund_order SET status = 'SUCCESS' WHERE out_request_no = 'DONE'");
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "PENDING", "3"));
        second.reserveMerchantRefund("M1", refund("ONE", "T1", "REMAINDER", "3"));
        assertThat(second.view("ONE").refundedAmount()).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT SUM(refund_amount) FROM refund_order WHERE status = 'PENDING'", BigDecimal.class))
                .isEqualByComparingTo("6");
        assertThatThrownBy(() -> second.reserveMerchantRefund("M1", refund("ONE", "T1", "EXCESS", "0.01")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("REFUND_PENDING"));
    }

    @Test
    void merchantCannotOverwriteAnotherMerchantsRefundRequestNumber() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "SHARED_ID", "2"));
        assertThatThrownBy(() -> second.reserveMerchantRefund("M2", refund("TWO", "T2", "SHARED_ID", "5")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("REFUND_CONFLICT"));
        Map<String, Object> row = jdbc.queryForMap("SELECT merchant_id, out_trade_no, refund_amount FROM refund_order WHERE out_request_no = 'SHARED_ID'");
        assertThat(row).containsEntry("merchant_id", "M1").containsEntry("out_trade_no", "ONE");
        assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("2");
    }

    @Test
    void concurrentCrossMerchantRefundIdCollisionHasOneWinnerAndDoesNotOverwrite() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> reserve(first, "M1", refund("ONE", "T1", "RACE_ID", "2"), start));
            var two = executor.submit(() -> reserve(second, "M2", refund("TWO", "T2", "RACE_ID", "5"), start));
            start.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder("OK", "REFUND_CONFLICT");
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT merchant_id, out_trade_no, refund_amount FROM refund_order WHERE out_request_no = 'RACE_ID'");
        if ("M1".equals(row.get("merchant_id"))) {
            assertThat(row.get("out_trade_no")).isEqualTo("ONE");
            assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("2");
        } else {
            assertThat(row.get("merchant_id")).isEqualTo("M2");
            assertThat(row.get("out_trade_no")).isEqualTo("TWO");
            assertThat((BigDecimal) row.get("refund_amount")).isEqualByComparingTo("5");
        }
    }

    @Test
    void paymentReservationPersistsAcrossServiceInstancesAndCannotChangeOwner() {
        first.reserveMerchantPayment("M1", "First", "original", payment("NEW", "10", "https://first.example/notify"));
        assertThatThrownBy(() -> second.reserveMerchantPayment("M2", "Second", "other", payment("NEW", "1", "https://other.example/notify")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("ORDER_CONFLICT"));
        DemoOrderView original = second.view("NEW");
        assertThat(original.merchantId()).isEqualTo("M1");
        assertThat(original.channelId()).isEqualTo("original");
        assertThat(original.amount()).isEqualByComparingTo("10");
        assertThat(second.merchantNotifyTarget("NEW").orElseThrow().notifyUrl()).isEqualTo("https://first.example/notify");
    }

    private void seed(String order, String trade, String merchant) {
        jdbc.update("INSERT INTO pay_order (out_trade_no, trade_no, merchant_id, channel_id, product, subject, amount, status) VALUES (?, ?, ?, 'original', 'ALIPAY_WAP', 'Item', 10, 'COMPLETED')",
                order, trade, merchant);
    }

    private static String reserve(DemoOrderService service, String merchant, RefundCreateRequest request, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            service.reserveMerchantRefund(merchant, request);
            return "OK";
        } catch (MerchantApiException error) {
            return error.code();
        }
    }

    private static PayCreateRequest payment(String order, String amount, String notify) {
        return new PayCreateRequest(PaymentProduct.ALIPAY_WAP, order, "Item", new BigDecimal(amount), null, null, null,
                null, null, notify, null, null, null, List.of("original"), Map.of(), null, null);
    }

    private static RefundCreateRequest refund(String order, String trade, String id, String amount) {
        return new RefundCreateRequest(order, trade, new BigDecimal(amount), id, null, null, List.of("original"), Map.of());
    }
}
