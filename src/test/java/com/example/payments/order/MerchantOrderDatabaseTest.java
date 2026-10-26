package com.example.payments.order;

import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.douyin.DouyinRefundEvidence;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.web.DouyinNotifyController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import com.example.payments.merchant.api.MerchantApiException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

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

    @Test
    void refundEvidenceMustMatchChannelOriginalOrderTransactionAndAmounts() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        DouyinRefundEvidence matching = evidence("ONE", "T1", "4", "10");
        assertThatThrownBy(() -> second.recordDouyinRefundNotify("other", matching, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
        for (DouyinRefundEvidence mismatch : List.of(evidence("TWO", "T1", "4", "10"),
                evidence("ONE", "T2", "4", "10"), evidence("ONE", "T1", "0.01", "10"),
                evidence("ONE", "T1", "4", "9.99"))) {
            assertThatThrownBy(() -> second.recordDouyinRefundNotify("original", mismatch, "SUCCESS"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE out_request_no='R1'", String.class)).isEqualTo("PENDING");
        assertThat(second.view("ONE").refundedAmount()).isEqualByComparingTo("0");
        jdbc.update("UPDATE refund_order SET merchant_id='M2' WHERE out_request_no='R1'");
        assertThatThrownBy(() -> second.recordDouyinRefundNotify("original", matching, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchingRefundIsIdempotentAndLatePendingOrFailureCannotReverseSuccess() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        DouyinRefundEvidence matching = evidence("ONE", "T1", "4", "10");
        first.recordDouyinRefundNotify("original", matching, "SUCCESS");
        Object completedAt = jdbc.queryForObject("SELECT completed_at FROM refund_order WHERE out_request_no='R1'", Object.class);
        second.recordDouyinRefundNotify("original", matching, "SUCCESS");
        second.recordDouyinRefundNotify("original", matching, "PROCESSING");
        second.recordDouyinRefundNotify("original", matching, "FAILED");
        assertThat(second.view("ONE").refundedAmount()).isEqualByComparingTo("4");
        assertThat(second.view("ONE").status()).isEqualTo(DemoOrderStatus.PARTIALLY_REFUNDED);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE out_request_no='R1'", String.class)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM refund_order WHERE out_request_no='R1'", Object.class)).isEqualTo(completedAt);
    }

    @Test
    void refundUpdateRollsBackWhenPaymentUpdateFails() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        jdbc.execute("ALTER TABLE pay_order ADD CONSTRAINT reject_refund_update CHECK (status <> 'PARTIALLY_REFUNDED')");
        assertThatThrownBy(() -> first.recordDouyinRefundNotify("original", evidence("ONE", "T1", "4", "10"), "SUCCESS"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE out_request_no='R1'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT completed_at FROM refund_order WHERE out_request_no='R1'", Object.class)).isNull();
        assertThat(second.view("ONE").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    @Test
    void platformRefundIdCannotChangeAfterReceiptOrInitialUpstreamResponse() {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        jdbc.update("UPDATE refund_order SET raw_response=? WHERE out_request_no='R1'", "{\"raw\":{\"refund_id\":\"DY-R1\"}}");
        DouyinRefundEvidence different = new DouyinRefundEvidence("R1", "ONE", "T1", "DY-OTHER", new BigDecimal("4"), new BigDecimal("10"));
        assertThatThrownBy(() -> first.recordDouyinRefundNotify("original", different, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
        first.recordDouyinRefundNotify("original", evidence("ONE", "T1", "4", "10"), "PROCESSING");
        assertThatThrownBy(() -> second.recordDouyinRefundNotify("original", different, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE out_request_no='R1'", String.class)).isEqualTo("PENDING");
    }

    @Test
    void delayedRefundSubmissionResponseCannotEraseConfirmedPlatformIdentity() {
        RefundCreateRequest request = refund("ONE", "T1", "R1", "4");
        first.reserveMerchantRefund("M1", request);
        first.recordDouyinRefundNotify("original", evidence("ONE", "T1", "4", "10"), "SUCCESS");
        GatewayResponse delayed = new GatewayResponse("original", PaymentStatus.PENDING, "SUCCESS", "Accepted", "ONE", "T1",
                null, null, Map.of(), List.of());
        second.recordRefund(request, delayed);
        DouyinRefundEvidence changed = new DouyinRefundEvidence("R1", "ONE", "T1", "DY-OTHER", new BigDecimal("4"), new BigDecimal("10"));
        assertThatThrownBy(() -> first.recordDouyinRefundNotify("original", changed, "SUCCESS"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(second.view("ONE").refundedAmount()).isEqualByComparingTo("4");
        GatewayResponse changedResponse = new GatewayResponse("original", PaymentStatus.SUCCESS, "SUCCESS", "Success", "ONE", "T1",
                null, null, Map.of("refund_id", "DY-OTHER"), List.of());
        assertThatThrownBy(() -> second.recordRefund(request, changedResponse)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRefundsUseCurrentCommittedTotalsEvenWhenDatabaseDefaultsToRepeatableRead() throws Exception {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R2", "6"));
        var repeatableReadDataSource = new DelegatingDataSource(jdbc.getDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                return connection;
            }
        };
        CountDownLatch identified = new CountDownLatch(2);
        AtomicInteger checked = new AtomicInteger();
        JdbcTemplate concurrentJdbc = new JdbcTemplate(repeatableReadDataSource) {
            @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (sql.equals("SELECT out_trade_no FROM refund_order WHERE out_request_no = ?")
                        || sql.equals("SELECT out_trade_no FROM pay_order WHERE out_trade_no = ? FOR UPDATE")) {
                    assertThat(execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation))
                            .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                    checked.incrementAndGet();
                }
                T result = super.queryForObject(sql, type, args);
                if (sql.equals("SELECT out_trade_no FROM refund_order WHERE out_request_no = ?")) {
                    // Both instances identify their refund before either takes the shared original-order lock.
                    identified.countDown();
                    try { assertThat(identified.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                }
                return result;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jdbcTemplate", concurrentJdbc);
        DemoOrderService one = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
        DemoOrderService two = new DemoOrderService(beans.getBeanProvider(JdbcTemplate.class));
        DouyinRefundEvidence refundTwo = new DouyinRefundEvidence("R2", "ONE", "T1", "DY-R2", new BigDecimal("6"), new BigDecimal("10"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> one.recordDouyinRefundNotify("original", evidence("ONE", "T1", "4", "10"), "SUCCESS"));
            var secondResult = executor.submit(() -> two.recordDouyinRefundNotify("original", refundTwo, "SUCCESS"));
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        }
        assertThat(first.view("ONE").refundedAmount()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE out_trade_no='ONE'", String.class)).isEqualTo("REFUNDED");
        // The late submission-result path must also override the database's default isolation.
        two.recordRefund(refund("ONE", "T1", "R1", "4"), new GatewayResponse("original", PaymentStatus.PENDING,
                "SUCCESS", "Accepted", "ONE", "T1", null, null, Map.of(), List.of()));
        assertThat(checked.get()).isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM pay_order WHERE out_trade_no='ONE'", String.class)).isEqualTo("REFUNDED");
    }

    @Test
    void authenticatedEncryptedCallbackCannotCrossChannelsAndAcceptsTheOfficialEnvelopeMerchant() throws Exception {
        first.reserveMerchantRefund("M1", refund("ONE", "T1", "R1", "4"));
        ObjectMapper mapper = new ObjectMapper();
        String key = "0123456789abcdef0123456789abcdef";
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        for (String id : List.of("original", "other")) {
            PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
            channel.setId(id);
            channel.setProvider("DOUYIN");
            channel.getDouyin().setMchId("MCH1");
            channel.getDouyin().setEncryptKey(key);
            properties.getChannels().add(channel);
        }
        // Simulate an already verified upstream signature; AES-GCM and all subsequent identity checks are real.
        DouyinPayClient client = new DouyinPayClient(mapper) {
            @Override
            public boolean verifyNotification(PaymentGatewayProperties.Channel channel, String timestamp, String nonce,
                                              String signature, String serial, String body) { return true; }
        };
        DouyinNotifyController controller = new DouyinNotifyController(new ChannelRegistry(properties), client, first, null, mapper);
        Map<String, Object> payload = Map.of("out_refund_no", "R1", "out_trade_no", "ONE", "transaction_id", "T1",
                "refund_id", "DY-R1", "refund_status", "SUCCESS", "amount", Map.of("refund", 400, "total", 1000));
        String nonce = "012345678901";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("refund".getBytes(StandardCharsets.UTF_8));
        String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(mapper.writeValueAsBytes(payload)));
        String body = mapper.writeValueAsString(Map.of("event_type", "REFUND.SUCCESS", "resource", Map.of(
                "algorithm", "AEAD-AES-256-GCM", "original_type", "refund", "nonce", nonce,
                "associated_data", "refund", "ciphertext", ciphertext, "mchid", "MCH1")));
        assertThat(controller.notify("other", new HttpHeaders(), body).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_order WHERE out_request_no='R1'", String.class)).isEqualTo("PENDING");
        assertThat(controller.notify("original", new HttpHeaders(), body).getStatusCode().value()).isEqualTo(200);
        assertThat(second.view("ONE").refundedAmount()).isEqualByComparingTo("4");
    }

    private static DouyinRefundEvidence evidence(String order, String trade, String amount, String total) {
        return new DouyinRefundEvidence("R1", order, trade, "DY-R1", new BigDecimal(amount), new BigDecimal(total));
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
