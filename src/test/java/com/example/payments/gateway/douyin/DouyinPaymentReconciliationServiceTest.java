package com.example.payments.gateway.douyin;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinPaymentReconciliationServiceTest {

    @Test
    void queriesPendingDouyinOrderUsingOriginalOrderAndChannel() {
        DemoOrderService orderService = mock(DemoOrderService.class);
        PaymentGatewayService gatewayService = mock(PaymentGatewayService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        DemoOrderView pending = pendingOrder();
        when(orderService.pendingDouyinOrders(100)).thenReturn(List.of(pending));
        when(gatewayService.query(org.mockito.ArgumentMatchers.any(PaymentQueryRequest.class)))
                .thenReturn(new GatewayResponse(
                        "douyin-main",
                        PaymentStatus.SUCCESS,
                        "SUCCESS",
                        "payment succeeded",
                        pending.outTradeNo(),
                        "DOUYIN-TRANSACTION-001",
                        null,
                        null,
                        Map.of("trade_state", "SUCCESS"),
                        List.of()
                ));

        new DouyinPaymentReconciliationService(orderService, gatewayService, channelRegistry).reconcilePendingOrders();

        ArgumentCaptor<PaymentQueryRequest> captor = ArgumentCaptor.forClass(PaymentQueryRequest.class);
        verify(gatewayService).query(captor.capture());
        assertThat(captor.getValue().outTradeNo()).isEqualTo(pending.outTradeNo());
        assertThat(captor.getValue().channelIds()).containsExactly("douyin-main");
    }

    @Test
    void refreshesOnlyDouyinOrdersFromVisibleOrderList() {
        DemoOrderService orderService = mock(DemoOrderService.class);
        PaymentGatewayService gatewayService = mock(PaymentGatewayService.class);
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        DemoOrderView pending = pendingOrder();
        PaymentGatewayProperties.Channel douyin = new PaymentGatewayProperties.Channel();
        douyin.setId("douyin-main");
        douyin.setProvider("DOUYIN");
        when(channelRegistry.find("douyin-main")).thenReturn(java.util.Optional.of(douyin));
        when(gatewayService.query(org.mockito.ArgumentMatchers.any(PaymentQueryRequest.class)))
                .thenReturn(new GatewayResponse(
                        "douyin-main", PaymentStatus.SUCCESS, "SUCCESS", "payment succeeded",
                        pending.outTradeNo(), "DOUYIN-TRANSACTION-001", null, null, Map.of(), List.of()
                ));

        new DouyinPaymentReconciliationService(orderService, gatewayService, channelRegistry)
                .reconcileVisibleOrders(List.of(pending));

        ArgumentCaptor<PaymentQueryRequest> captor = ArgumentCaptor.forClass(PaymentQueryRequest.class);
        verify(gatewayService).query(captor.capture());
        assertThat(captor.getValue().channelIds()).containsExactly("douyin-main");
    }

    private static DemoOrderView pendingOrder() {
        return new DemoOrderView(
                "DOUYIN-ORDER-001",
                null,
                "douyin-main",
                "M10001",
                "merchant",
                "DOUYIN_H5",
                "cashier payment",
                new BigDecimal("3.00"),
                "3.00",
                BigDecimal.ZERO,
                "0.00",
                new BigDecimal("3.00"),
                "3.00",
                BigDecimal.ZERO,
                "0.00",
                new BigDecimal("3.00"),
                "3.00",
                DemoOrderStatus.UNPAID,
                "unpaid",
                "2026-07-22 12:00:00",
                false,
                false,
                false
        );
    }
}
