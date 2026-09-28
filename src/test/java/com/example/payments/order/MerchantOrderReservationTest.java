package com.example.payments.order;

import com.example.payments.domain.*;
import com.example.payments.merchant.api.MerchantApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantOrderReservationTest {
    private final DemoOrderService orders = new DemoOrderService();

    @Test
    void duplicateReservationCannotChangeOwnerChannelAmountOrNotification() {
        orders.reserveMerchantPayment("M1", "First", "original", payment("ORDER", "10", "https://first.example/notify"));
        assertThatThrownBy(() -> orders.reserveMerchantPayment("M2", "Second", "other", payment("ORDER", "1", "https://other.example/notify")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("ORDER_CONFLICT"));
        assertThat(orders.view("ORDER").merchantId()).isEqualTo("M1");
        assertThat(orders.view("ORDER").channelId()).isEqualTo("original");
        assertThat(orders.view("ORDER").amount()).isEqualByComparingTo("10");
        assertThat(orders.merchantNotifyTarget("ORDER").orElseThrow().notifyUrl()).isEqualTo("https://first.example/notify");
    }

    @Test
    void databaseUniqueConstraintRejectsDuplicateInsteadOfUpdatingExistingRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked") ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        doAnswer(call -> {
            if (((String) call.getArgument(0)).contains("INSERT INTO pay_order")) throw new DuplicateKeyException("duplicate");
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        DemoOrderService databaseOrders = new DemoOrderService(provider);
        assertThatThrownBy(() -> databaseOrders.reserveMerchantPayment("M1", "Merchant", "original", payment("ORDER", "10", "https://first.example/notify")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("ORDER_CONFLICT"));
        verify(jdbc, never()).update(startsWith("UPDATE pay_order"), any(Object[].class));
    }

    @Test
    void tradeNoLookupIsScopedToMerchantAndAmbiguousSameMerchantIdsRequireOrderNo() {
        seed("ONE", "TRADE", "M1"); seed("TWO", "TRADE", "M2");
        assertThat(orders.resolveMerchantOrder("M1", null, "TRADE").outTradeNo()).isEqualTo("ONE");
        seed("THREE", "TRADE", "M1");
        assertThatThrownBy(() -> orders.resolveMerchantOrder("M1", null, "TRADE")).isInstanceOf(MerchantApiException.class);
        assertThat(orders.resolveMerchantOrder("M1", "ONE", "TRADE").outTradeNo()).isEqualTo("ONE");
    }

    @Test
    void refundRequestNumberCannotBeReusedAcrossOrdersOrMerchants() {
        seed("ONE", "T1", "M1"); seed("TWO", "T2", "M2");
        orders.reserveMerchantRefund("M1", refund("ONE", "T1", "REFUND", "3"));
        assertThatThrownBy(() -> orders.reserveMerchantRefund("M2", refund("TWO", "T2", "REFUND", "3")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("REFUND_CONFLICT"));
    }

    @Test
    void pendingRefundReservesBalanceWithoutIncreasingSuccessfulRefundAmount() {
        seed("ONE", "T1", "M1");
        RefundCreateRequest first = refund("ONE", "T1", "REFUND1", "7");
        orders.reserveMerchantRefund("M1", first);
        orders.recordRefund(first, result(PaymentStatus.PENDING));
        assertThat(orders.view("ONE").refundedAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> orders.reserveMerchantRefund("M1", refund("ONE", "T1", "REFUND2", "4")))
                .isInstanceOfSatisfying(MerchantApiException.class, error -> assertThat(error.code()).isEqualTo("REFUND_PENDING"));
        orders.reserveMerchantRefund("M1", refund("ONE", "T1", "REFUND3", "3"));
    }

    @Test
    void repeatedAndOutOfOrderRefundResponsesCountSuccessOnce() {
        seed("ONE", "T1", "M1");
        RefundCreateRequest refund = refund("ONE", "T1", "REFUND1", "7");
        orders.reserveMerchantRefund("M1", refund);
        orders.recordRefund(refund, result(PaymentStatus.SUCCESS));
        orders.recordRefund(refund, result(PaymentStatus.SUCCESS));
        orders.recordRefund(refund, result(PaymentStatus.PENDING));
        assertThat(orders.view("ONE").refundedAmount()).isEqualByComparingTo("7");
        assertThat(orders.view("ONE").status()).isEqualTo(DemoOrderStatus.PARTIALLY_REFUNDED);
        assertThatThrownBy(() -> orders.reserveMerchantRefund("M1", refund)).isInstanceOf(MerchantApiException.class);
    }

    @Test
    void definiteFailedRefundReleasesBalanceButNeverReusesRequestNumber() {
        seed("ONE", "T1", "M1");
        RefundCreateRequest failed = refund("ONE", "T1", "REFUND1", "10");
        orders.reserveMerchantRefund("M1", failed);
        orders.recordRefund(failed, result(PaymentStatus.FAILED));
        assertThat(orders.view("ONE").refundedAmount()).isEqualByComparingTo("0");
        orders.reserveMerchantRefund("M1", refund("ONE", "T1", "REFUND2", "10"));
        assertThatThrownBy(() -> orders.reserveMerchantRefund("M1", failed)).isInstanceOf(MerchantApiException.class);
    }

    @Test
    void callbackSuccessIsNotDowngradedByDelayedCreateResponse() {
        orders.reserveMerchantPayment("M1", "Merchant", "original", payment("ONE", "10", null));
        orders.recordPaymentResult("ONE", "T1", "original", PaymentStatus.SUCCESS);
        orders.recordPaymentCreated("ONE", "T1", "original", "M1", "Merchant", "ALIPAY_WAP", new BigDecimal("10"), false, PaymentStatus.CREATED);
        assertThat(orders.view("ONE").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    private void seed(String order, String trade, String owner) {
        orders.recordPaymentCreated(order, trade, "original", owner, "Merchant", "ALIPAY_WAP", new BigDecimal("10"), false, PaymentStatus.SUCCESS);
    }

    private static PayCreateRequest payment(String order, String amount, String notify) {
        return new PayCreateRequest(PaymentProduct.ALIPAY_WAP, order, "Item", new BigDecimal(amount), null, null, null,
                null, null, notify, null, null, null, List.of("original"), Map.of(), null, null);
    }

    private static RefundCreateRequest refund(String order, String trade, String id, String amount) {
        return new RefundCreateRequest(order, trade, new BigDecimal(amount), id, null, null, List.of("original"), Map.of());
    }

    private static GatewayResponse result(PaymentStatus status) {
        return new GatewayResponse("original", status, "OK", "OK", "ONE", "T1", null, null, Map.of(), List.of());
    }
}
