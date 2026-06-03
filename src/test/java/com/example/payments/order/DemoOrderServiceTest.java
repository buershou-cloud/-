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
