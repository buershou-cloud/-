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
import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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
        channel.getDouyin().setMchId("MCH1");
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
                        Map.of("status", "SUCCESS", "out_refund_no", "REFUND-1001",
                                "out_trade_no", "ORDER-1001", "refund_id", "DY-REFUND-1001", "amount", Map.of("refund", 100)),
                        List.of()
                ));

        new DouyinRefundReconciliationService(orderService, channelRegistry, paymentProvider)
                .reconcilePendingRefunds();

        verify(paymentProvider).queryRefund(channel, "REFUND-1001");
        verify(orderService).recordDouyinRefundNotify(
                "douyin-main",
                new DouyinRefundEvidence("REFUND-1001", "ORDER-1001", null, "DY-REFUND-1001", new BigDecimal("1.00"), null),
                "SUCCESS"
        );
    }

    @Test
    void aQueryForOneRefundCannotApplyAnotherRefundsResponse() {
        DemoOrderService orders = mock(DemoOrderService.class);
        ChannelRegistry registry = mock(ChannelRegistry.class);
        DouyinPaymentProvider provider = mock(DouyinPaymentProvider.class);
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("dy");
        channel.setProvider("DOUYIN");
        channel.getDouyin().setMchId("MCH1");
        when(orders.pendingDouyinRefunds(100)).thenReturn(List.of(new DouyinPendingRefund("dy", "ORDER", "EXPECTED")));
        when(registry.find("dy")).thenReturn(Optional.of(channel));
        when(provider.queryRefund(channel, "EXPECTED")).thenReturn(new GatewayResponse("dy", PaymentStatus.SUCCESS,
                "SUCCESS", "Success", "ORDER", "DY-ID", null, null, Map.of("out_refund_no", "DIFFERENT",
                "out_trade_no", "ORDER", "refund_id", "DY-ID", "amount", Map.of("refund", 100)), List.of()));
        new DouyinRefundReconciliationService(orders, registry, provider).reconcilePendingRefunds();
        verify(orders, never()).recordDouyinRefundNotify(anyString(), any(), anyString());
    }
}
