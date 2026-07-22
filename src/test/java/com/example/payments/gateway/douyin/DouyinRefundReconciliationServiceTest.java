package com.example.payments.gateway.douyin;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DouyinPendingRefund;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinRefundReconciliationServiceTest {

    @Test
    void marksPendingRefundSuccessfulAfterGatewayQuery() {
        DemoOrderService orderService = mock(DemoOrderService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        DouyinPaymentProvider paymentProvider = mock(DouyinPaymentProvider.class);
        DouyinPendingRefund refund = new DouyinPendingRefund(
                "douyin-main",
                "ORDER-1001",
                "REFUND-1001"
        );
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("douyin-main");
        channel.setProvider("DOUYIN");
        when(orderService.pendingDouyinRefunds(100)).thenReturn(List.of(refund));
        when(channelRegistry.find("douyin-main")).thenReturn(Optional.of(channel));
        when(paymentProvider.queryRefund(channel, "REFUND-1001"))
                .thenReturn(new GatewayResponse(
                        "douyin-main",
                        PaymentStatus.SUCCESS,
                        "SUCCESS",
                        "refund succeeded",
                        "ORDER-1001",
                        "DY-REFUND-1001",
                        null,
                        null,
                        Map.of("refund_status", "SUCCESS"),
                        List.of()
                ));

        new DouyinRefundReconciliationService(orderService, channelRegistry, paymentProvider)
                .reconcilePendingRefunds();

        verify(paymentProvider).queryRefund(channel, "REFUND-1001");
        verify(orderService).recordDouyinRefundNotify(
                "REFUND-1001",
                "SUCCESS",
                "DY-REFUND-1001"
        );
    }
}
