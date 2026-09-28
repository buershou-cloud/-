package com.example.payments.web;

import com.example.payments.complaint.ComplaintAutoQueryService;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.merchant.api.MerchantApiException;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentControllerPublicPayTest {
    private final DemoOrderService orders = new DemoOrderService();
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final PaymentController controller = new PaymentController(gateway, mock(ComplaintAutoQueryService.class),
            mock(OnboardingRecordService.class), mock(ComplaintRecordService.class), orders);

    @Test
    void anonymousClientCannotForgeInternalMerchantMarker() {
        assertThatThrownBy(() -> controller.pay(request("NEW", Map.of("merchantApiRequest", true)), new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void publicExtraCannotOverrideReservedOrderAmountOrOperation() {
        for (String key : List.of("out_trade_no", "trade_no", "out_order_no", "out_request_no", "out_refund_no",
                "total_amount", "refund_amount", "amount", "alipay_method", "preauth_method", "outTradeNo")) {
            assertThatThrownBy(() -> controller.pay(request("NEW", Map.of(key, "OVERRIDE")), new MockHttpServletRequest()))
                    .as(key).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void anonymousPaymentCannotOverwriteAnExistingMerchantOrder() {
        orders.recordPaymentCreated("EXISTING", "TRADE", "ali", "VICTIM", "Merchant", "支付宝", new BigDecimal("12.00"), false, PaymentStatus.SUCCESS);

        assertThatThrownBy(() -> controller.pay(request("EXISTING", Map.of("merchantId", "ATTACKER")), new MockHttpServletRequest()))
                .isInstanceOf(MerchantApiException.class);

        verifyNoInteractions(gateway);
        assertThat(orders.view("EXISTING").merchantId()).isEqualTo("VICTIM");
        assertThat(orders.view("EXISTING").tradeNo()).isEqualTo("TRADE");
    }

    @Test
    void newCashierPaymentPreservesItsOrdinaryFields() {
        controller.pay(request("NEW", Map.of("cashier", true)), new MockHttpServletRequest());

        ArgumentCaptor<PayCreateRequest> captor = ArgumentCaptor.forClass(PayCreateRequest.class);
        verify(gateway).payPublic(captor.capture());
        assertThat(captor.getValue().outTradeNo()).isEqualTo("NEW");
        assertThat(captor.getValue().extra()).containsEntry("cashier", true).doesNotContainKey("merchantApiRequest");
    }

    private static PayCreateRequest request(String order, Map<String, Object> extra) {
        return new PayCreateRequest(PaymentProduct.ALIPAY_F2F, order, "Subject", new BigDecimal("1.00"),
                null, null, null, null, null, null, null, null, null, List.of("ali"), extra, null, null);
    }
}
