package com.example.payments.order;

import com.example.payments.merchant.DemoMerchantService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Unfiltered accounting summaries, using receipt/refund completion dates rather than order creation dates. */
@Service
public class OrderSummaryService {
    private final JdbcTemplate jdbc;
    private final DemoOrderService orders;
    private final DemoMerchantService merchants;

    @Autowired
    public OrderSummaryService(ObjectProvider<JdbcTemplate> jdbcProvider, DemoOrderService orders,
                               DemoMerchantService merchants) {
        this(jdbcProvider.getIfAvailable(), orders, merchants);
    }

    OrderSummaryService(JdbcTemplate jdbc, DemoOrderService orders, DemoMerchantService merchants) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.merchants = merchants;
    }

    public Summary summary(String todayText, String monthText) {
        LocalDate today;
        YearMonth month;
        try {
            if (todayText == null || !todayText.matches("\\d{4}-\\d{2}-\\d{2}")
                    || monthText == null || !monthText.matches("\\d{4}-\\d{2}")) {
                throw new IllegalArgumentException("统计日期必须为 yyyy-MM-dd，月份必须为 yyyy-MM");
            }
            today = LocalDate.parse(todayText);
            month = YearMonth.parse(monthText);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("统计日期或月份无效", ex);
        }
        if (jdbc == null) return summarize(memoryDays(), today, month);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> summarize(databaseDays(), today, month));
    }

    private Map<LocalDate, Day> databaseDays() {
        Map<LocalDate, Day> days = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DATE(o.paid_at) AS receipt_day, COUNT(*) AS receipt_count,
                       SUM(o.amount) AS receipt_amount,
                       SUM(o.amount * COALESCE(m.fee_rate, 0) / 100) AS fee_amount
                FROM pay_order o
                LEFT JOIN merchant m ON m.merchant_id = o.merchant_id
                WHERE o.status IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                  AND o.pre_authorization = FALSE
                GROUP BY DATE(o.paid_at)
                """, rs -> {
            Date date = rs.getDate("receipt_day");
            Day day = day(days, date == null ? null : date.toLocalDate());
            day.payCount += rs.getLong("receipt_count");
            day.payAmount = day.payAmount.add(rs.getBigDecimal("receipt_amount"));
            day.feeAmount = day.feeAmount.add(rs.getBigDecimal("fee_amount"));
        });
        jdbc.query("""
                SELECT DATE(completed_at) AS refund_day, COUNT(*) AS refund_count,
                       SUM(refund_amount) AS refund_amount
                FROM refund_order
                WHERE status = 'SUCCESS'
                GROUP BY DATE(completed_at)
                """, rs -> {
            Date date = rs.getDate("refund_day");
            Day day = day(days, date == null ? null : date.toLocalDate());
            day.refundCount += rs.getLong("refund_count");
            day.refundAmount = day.refundAmount.add(rs.getBigDecimal("refund_amount"));
        });
        return days;
    }

    private Map<LocalDate, Day> memoryDays() {
        Map<LocalDate, Day> days = new LinkedHashMap<>();
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        merchants.list().forEach(merchant -> rates.put(merchant.merchantId(), merchant.feeRate()));
        for (DemoOrderService.PaymentReceipt receipt : orders.successfulPaymentReceipts()) {
            Day day = day(days, receipt.paidAt() == null ? null : receipt.paidAt().toLocalDate());
            day.payCount++;
            day.payAmount = day.payAmount.add(receipt.amount());
            day.feeAmount = day.feeAmount.add(receipt.amount().multiply(
                    rates.getOrDefault(receipt.merchantId(), BigDecimal.ZERO)).movePointLeft(2));
        }
        for (DemoOrderService.RefundReceipt receipt : orders.successfulRefundReceipts()) {
            Day day = day(days, receipt.completedAt() == null ? null : receipt.completedAt().toLocalDate());
            day.refundCount++;
            day.refundAmount = day.refundAmount.add(receipt.amount());
        }
        return days;
    }

    private static Day day(Map<LocalDate, Day> days, LocalDate date) {
        return days.computeIfAbsent(date, ignored -> new Day());
    }

    private static Summary summarize(Map<LocalDate, Day> days, LocalDate today, YearMonth month) {
        Day daily = days.getOrDefault(today, new Day());
        BigDecimal monthlyAmount = BigDecimal.ZERO;
        long monthlyCount = 0;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        List<ReportRow> rows = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>(days.keySet());
        dates.sort(Comparator.nullsLast(Comparator.reverseOrder()));
        for (LocalDate date : dates) {
            Day day = days.get(date);
            paid = paid.add(day.payAmount);
            refunded = refunded.add(day.refundAmount);
            fees = fees.add(day.feeAmount);
            if (date != null && YearMonth.from(date).equals(month)) {
                monthlyCount += day.payCount;
                monthlyAmount = monthlyAmount.add(day.payAmount);
            }
            // Missing historical timestamps are visible without assigning them to a fabricated day.
            rows.add(new ReportRow(date == null ? "时间未记录" : date.toString(), day.payCount,
                    money(day.payAmount), money(day.refundAmount), "UNCONFIRMED"));
        }
        // These are accounting estimates, not evidence of platform settlement or reconciliation.
        return new Summary(money(daily.payAmount), daily.payCount, money(monthlyAmount), monthlyCount,
                money(daily.refundAmount), daily.refundCount,
                money(paid.subtract(refunded).subtract(fees).max(BigDecimal.ZERO)), money(fees), null,
                List.copyOf(rows));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Day {
        long payCount;
        long refundCount;
        BigDecimal payAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = BigDecimal.ZERO;
        BigDecimal feeAmount = BigDecimal.ZERO;
    }

    public record ReportRow(String date, long count, BigDecimal payAmount,
                            BigDecimal refundAmount, String settlementStatus) { }

    public record Summary(BigDecimal todayTradeAmount, long todayTradeCount,
                          BigDecimal monthTradeAmount, long monthTradeCount,
                          BigDecimal todayRefundAmount, long todayRefundCount,
                          BigDecimal reportReceivable, BigDecimal reportFee, Integer reportMismatch,
                          List<ReportRow> reportRows) { }
}
