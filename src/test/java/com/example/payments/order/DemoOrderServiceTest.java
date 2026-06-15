package com.example.payments.order;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.RefundCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoOrderServiceTest {

    @Test
    void shareableByChannelSkipsUnavailableAndAlreadySharedOrders() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "ORDER-SHARE-001",
                "TRADE-SHARE-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("10.00"),
                false,
                PaymentStatus.CREATED
        );
        service.complete("ORDER-SHARE-001");
        service.recordPaymentCreated(
                "ORDER-REFUND-001",
                "TRADE-REFUND-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("20.00"),
                false,
                PaymentStatus.CREATED
        );
        service.complete("ORDER-REFUND-001");
        service.refund("ORDER-REFUND-001");

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .contains("ORDER-SHARE-001")
                .doesNotContain("ORDER-REFUND-001");

        service.markProfitShared("ORDER-SHARE-001");

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .doesNotContain("ORDER-SHARE-001");
        assertThat(service.shareableByChannel("ali-main", true))
                .extracting(DemoOrderView::outTradeNo)
                .contains("ORDER-SHARE-001");
    }

    @Test
    void recordsPartialRefundsUntilFullAmount() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "ORDER-PARTIAL-001",
                "TRADE-PARTIAL-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("20.00"),
                false,
                PaymentStatus.CREATED
        );
        service.complete("ORDER-PARTIAL-001");

        RefundCreateRequest firstRefund = refundRequest("ORDER-PARTIAL-001", "TRADE-PARTIAL-001", "5.00", "RF_1");
        service.ensureRefundable(firstRefund.outTradeNo(), firstRefund.tradeNo(), firstRefund.refundAmount());
        DemoOrderView partial = service.recordRefund(firstRefund, refundResponse(firstRefund));

        assertThat(partial.status()).isEqualTo(DemoOrderStatus.PARTIALLY_REFUNDED);
        assertThat(partial.refundedAmount()).isEqualByComparingTo("5.00");
        assertThat(partial.refundableAmount()).isEqualByComparingTo("15.00");

        RefundCreateRequest secondRefund = refundRequest("ORDER-PARTIAL-001", "TRADE-PARTIAL-001", "15.00", "RF_2");
        service.ensureRefundable(secondRefund.outTradeNo(), secondRefund.tradeNo(), secondRefund.refundAmount());
        DemoOrderView refunded = service.recordRefund(secondRefund, refundResponse(secondRefund));

        assertThat(refunded.status()).isEqualTo(DemoOrderStatus.REFUNDED);
        assertThat(refunded.refundedAmount()).isEqualByComparingTo("20.00");
        assertThat(refunded.refundableAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void preauthCaptureNotifyUpdatesOriginalOrderInsteadOfCreatingCaptureOrder() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "PREAUTH-001",
                "AUTH-001",
                "ali-main",
                "M10001",
                "merchant",
                "H5 preauth",
                new BigDecimal("1.00"),
                true,
                PaymentStatus.SUCCESS
        );

        DemoOrderView captured = service.recordAlipayNotify(
                "PREAUTH-001_PAY_1781530011597",
                "TRADE-CAPTURE-001",
                "ali-main",
                new BigDecimal("1.00"),
                "TRADE_SUCCESS"
        );

        assertThat(captured.outTradeNo()).isEqualTo("PREAUTH-001");
        assertThat(captured.tradeNo()).isEqualTo("TRADE-CAPTURE-001");
        assertThat(captured.status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(captured.preAuthorization()).isFalse();
        assertThat(service.recent())
                .extracting(DemoOrderView::outTradeNo)
                .containsExactly("PREAUTH-001");
    }

    @Test
    void orderListKeepsExistingPreauthCaptureChildOrderVisible() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "PREAUTH-OLD-001",
                "AUTH-OLD-001",
                "ali-main",
                "M10001",
                "merchant",
                "H5 preauth",
                new BigDecimal("1.00"),
                true,
                PaymentStatus.SUCCESS
        );
        service.recordPaymentCreated(
                "PREAUTH-OLD-001_PAY_1781530011597",
                "TRADE-CAPTURE-OLD-001",
                "ali-main",
                "M10001",
                "merchant",
                "preauth capture",
                new BigDecimal("1.00"),
                false,
                PaymentStatus.SUCCESS
        );

        List<DemoOrderView> orders = service.recent();

        assertThat(orders)
                .extracting(DemoOrderView::outTradeNo)
                .containsExactly("PREAUTH-OLD-001", "PREAUTH-OLD-001_PAY_1781530011597");
        assertThat(orders.get(0).tradeNo()).isEqualTo("AUTH-OLD-001");
        assertThat(orders.get(0).status()).isEqualTo(DemoOrderStatus.FROZEN);
        assertThat(orders.get(0).preAuthorization()).isTrue();
        assertThat(orders.get(1).tradeNo()).isEqualTo("TRADE-CAPTURE-OLD-001");
        assertThat(orders.get(1).status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    @Test
    void rejectsRefundsAboveRemainingAmount() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "ORDER-OVER-001",
                "TRADE-OVER-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("20.00"),
                false,
                PaymentStatus.CREATED
        );
        service.complete("ORDER-OVER-001");

        assertThatThrownBy(() -> service.ensureRefundable("ORDER-OVER-001", null, new BigDecimal("20.01")))
                .hasMessageContaining("cannot exceed remaining refundable amount");
    }

    private static RefundCreateRequest refundRequest(String outTradeNo, String tradeNo, String amount, String outRequestNo) {
        return new RefundCreateRequest(
                outTradeNo,
                tradeNo,
                new BigDecimal(amount),
                outRequestNo,
                "测试退款",
                null,
                List.of("ali-main"),
                Map.of()
        );
    }

    private static GatewayResponse refundResponse(RefundCreateRequest request) {
        return new GatewayResponse(
                "ali-main",
                PaymentStatus.SUCCESS,
                "10000",
                "Success",
                request.outTradeNo(),
                request.tradeNo(),
                null,
                null,
                Map.of("refund_amount", request.refundAmount().toPlainString()),
                List.of()
        );
    }
}
