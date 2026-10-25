package com.example.payments.order;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.RefundCreateRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderSummaryServiceTest {
    private JdbcTemplate jdbc;
    private OrderSummaryService service;

    @BeforeEach
    void setup() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:summary-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE merchant (merchant_id VARCHAR(64) PRIMARY KEY, fee_rate DECIMAL(10,4))");
        jdbc.execute("""
                CREATE TABLE pay_order (out_trade_no VARCHAR(96) PRIMARY KEY, merchant_id VARCHAR(64),
                    amount DECIMAL(18,2), status VARCHAR(32), pre_authorization BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMP, paid_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE refund_order (out_request_no VARCHAR(96) PRIMARY KEY, out_trade_no VARCHAR(96),
                    refund_amount DECIMAL(18,2), status VARCHAR(32), created_at TIMESTAMP, completed_at TIMESTAMP)
                """);
        jdbc.update("INSERT INTO merchant VALUES ('M1', 0.6)");
        service = new OrderSummaryService(jdbc, null, null);
    }

    @Test
    void countsPaymentOnReceiptDateAndRefundsOnTheirOwnCompletionDates() {
        payment("old-order-paid-today", "100.00", "PARTIALLY_REFUNDED", "2026-10-24 10:00:00", "2026-10-25 09:00:00");
        payment("old-payment", "80.00", "REFUNDED", "2026-09-01 10:00:00", "2026-09-01 10:05:00");
        refund("today-partial", "old-order-paid-today", "3.00", "SUCCESS", "2026-10-25 11:00:00");
        refund("today-cross-day", "old-payment", "30.00", "SUCCESS", "2026-10-25 12:00:00");
        refund("yesterday", "old-payment", "50.00", "SUCCESS", "2026-10-24 12:00:00");
        refund("pending", "old-order-paid-today", "7.00", "PENDING", null);
        refund("failed", "old-order-paid-today", "9.00", "FAILED", "2026-10-25 13:00:00");

        var summary = service.summary("2026-10-25", "2026-10");
        assertThat(summary.todayTradeAmount()).isEqualByComparingTo("100");
        assertThat(summary.todayTradeCount()).isEqualTo(1);
        assertThat(summary.monthTradeAmount()).isEqualByComparingTo("100");
        assertThat(summary.monthTradeCount()).isEqualTo(1);
        assertThat(summary.todayRefundAmount()).isEqualByComparingTo("33");
        assertThat(summary.todayRefundCount()).isEqualTo(2);
        assertThat(summary.reportFee()).isEqualByComparingTo("1.08");
        assertThat(summary.reportReceivable()).isEqualByComparingTo("95.92");
        assertThat(summary.reportRows()).extracting(OrderSummaryService.ReportRow::date)
                .containsExactly("2026-10-25", "2026-10-24", "2026-09-01");
        assertThat(summary.reportRows().get(1).refundAmount()).isEqualByComparingTo("50");
        assertThat(summary.reportRows().get(1).count()).isZero();
    }

    @Test
    void frozenUnpaidAndFailedOrdersAreNotReceiptsAndDatesDoNotProveSettlement() {
        payment("paid", "10", "COMPLETED", "2026-10-01 10:00:00", "2026-10-01 10:01:00");
        payment("unpaid", "20", "UNPAID", "2026-10-25 10:00:00", null);
        payment("closed", "30", "CLOSED", "2026-10-25 10:00:00", null);
        payment("frozen", "100", "FROZEN", "2026-10-25 10:00:00", null);
        payment("invalid-preauth-success", "100", "COMPLETED", "2026-10-25 10:00:00", "2026-10-25 10:01:00");
        jdbc.update("UPDATE pay_order SET pre_authorization = TRUE WHERE out_trade_no IN ('frozen', 'invalid-preauth-success')");
        var summary = service.summary("2026-10-25", "2026-10");
        assertThat(summary.todayTradeCount()).isZero();
        assertThat(summary.monthTradeAmount()).isEqualByComparingTo("10");
        assertThat(summary.reportRows()).singleElement().satisfies(row ->
                assertThat(row.settlementStatus()).isEqualTo("UNCONFIRMED"));
        assertThat(summary.reportMismatch()).isNull();
    }

    @Test
    void unknownHistoricalTimestampsStayVisibleWithoutBecomingTodaysActivity() {
        payment("historical", "100", "PARTIALLY_REFUNDED", "2026-10-25 10:00:00", null);
        refund("historical-refund", "historical", "30", "SUCCESS", null);
        var summary = service.summary("2026-10-25", "2026-10");
        assertThat(summary.todayTradeCount()).isZero();
        assertThat(summary.todayRefundAmount()).isEqualByComparingTo("0");
        assertThat(summary.monthTradeAmount()).isEqualByComparingTo("0");
        assertThat(summary.reportReceivable()).isEqualByComparingTo("69.40");
        assertThat(summary.reportRows()).singleElement().satisfies(row -> {
            assertThat(row.date()).isEqualTo("时间未记录");
            assertThat(row.payAmount()).isEqualByComparingTo("100");
            assertThat(row.refundAmount()).isEqualByComparingTo("30");
        });
    }

    @Test
    void reportingPeriodsUseCalendarBoundaries() {
        payment("last-day", "10", "COMPLETED", "2026-09-30 23:59:59", "2026-09-30 23:59:59");
        payment("first-day", "20", "COMPLETED", "2026-09-30 23:59:59", "2026-10-01 00:00:00");
        payment("next-month", "30", "COMPLETED", "2026-10-31 23:59:59", "2026-11-01 00:00:00");
        var summary = service.summary("2026-10-01", "2026-10");
        assertThat(summary.todayTradeAmount()).isEqualByComparingTo("20");
        assertThat(summary.monthTradeAmount()).isEqualByComparingTo("20");
        assertThat(summary.monthTradeCount()).isEqualTo(1);
    }

    @Test
    void memorySummariesUseActualCompletionEventsAndNeverFilteredOrderSearch() {
        DemoOrderService orders = mock(DemoOrderService.class);
        DemoMerchantService merchants = mock(DemoMerchantService.class);
        when(merchants.list()).thenReturn(List.of());
        when(orders.successfulPaymentReceipts()).thenReturn(List.of(
                new DemoOrderService.PaymentReceipt("old", "M1", new BigDecimal("100"),
                        LocalDateTime.of(2026, 10, 24, 10, 0))));
        when(orders.successfulRefundReceipts()).thenReturn(List.of(
                new DemoOrderService.RefundReceipt("R1", "old", new BigDecimal("30"),
                        LocalDateTime.of(2026, 10, 25, 10, 0))));
        var memory = new OrderSummaryService((JdbcTemplate) null, orders, merchants);
        var summary = memory.summary("2026-10-25", "2026-10");
        assertThat(summary.todayTradeAmount()).isEqualByComparingTo("0");
        assertThat(summary.monthTradeAmount()).isEqualByComparingTo("100");
        assertThat(summary.todayRefundAmount()).isEqualByComparingTo("30");
        assertThat(summary.todayRefundCount()).isEqualTo(1);
        verify(orders).successfulPaymentReceipts();
        verify(orders).successfulRefundReceipts();
        verifyNoMoreInteractions(orders);
    }

    @Test
    void actualMemoryReceiptAndRefundKeepTheirFirstCompletionTimeAcrossReplays() {
        DemoOrderService actualOrders = new DemoOrderService();
        actualOrders.recordPaymentCreated("payment", null, "channel", "M1", "Merchant", "ALIPAY_PAGE",
                "Order", new BigDecimal("100"), false, PaymentStatus.PENDING);
        assertThat(actualOrders.successfulPaymentReceipts()).isEmpty();
        actualOrders.recordPaymentResult("payment", "trade", "channel", PaymentStatus.SUCCESS,
                true, new BigDecimal("100"));
        var payment = actualOrders.successfulPaymentReceipts().getFirst();
        assertThat(payment.paidAt()).isNotNull();
        var request = new RefundCreateRequest("payment", "trade", new BigDecimal("30"), "refund", "reason",
                null, List.of("channel"), Map.of());
        var result = new GatewayResponse("channel", PaymentStatus.SUCCESS, "10000", "Success", "payment", "trade",
                null, null, Map.of(), List.of());
        actualOrders.recordRefund(request, result);
        var refund = actualOrders.successfulRefundReceipts().getFirst();
        assertThat(refund.completedAt()).isNotNull();
        actualOrders.recordRefund(request, result);
        actualOrders.recordPaymentResult("payment", "trade", "channel", PaymentStatus.SUCCESS,
                true, new BigDecimal("100"));
        assertThat(actualOrders.successfulPaymentReceipts()).containsExactly(payment);
        assertThat(actualOrders.successfulRefundReceipts()).containsExactly(refund);
        assertThat(actualOrders.view("payment").refundedAmount()).isEqualByComparingTo("30");
    }

    @Test
    void rejectsMalformedOrInvalidDates() {
        for (String date : List.of("2026-02-30", "2026-1-1", "2026-10-25' OR 1=1", "")) {
            assertThatThrownBy(() -> service.summary(date, "2026-10")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.summary("2026-10-25", "2026-13"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void payment(String id, String amount, String status, String created, String paid) {
        jdbc.update("INSERT INTO pay_order VALUES (?, 'M1', ?, ?, FALSE, ?, ?)",
                id, new BigDecimal(amount), status, timestamp(created), timestamp(paid));
    }

    private void refund(String id, String order, String amount, String status, String completed) {
        jdbc.update("INSERT INTO refund_order VALUES (?, ?, ?, ?, TIMESTAMP '2026-09-01 10:00:00', ?)",
                id, order, new BigDecimal(amount), status, timestamp(completed));
    }

    private static Timestamp timestamp(String value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
