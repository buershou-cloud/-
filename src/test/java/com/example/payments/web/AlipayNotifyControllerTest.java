package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.merchant.api.MerchantNotifyService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlipayNotifyControllerTest {

    @Test
    void preauthNotifyPrefersAuthNo() {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        AlipayOpenApiClient openApiClient = mock(AlipayOpenApiClient.class);
        DemoOrderService orderService = mock(DemoOrderService.class);
        MerchantNotifyService merchantNotifyService = mock(MerchantNotifyService.class);
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-main");
        when(channelRegistry.find("ali-main")).thenReturn(Optional.of(channel));
        when(openApiClient.verifyNotify(eq(channel), anyMap())).thenReturn(true);
        DemoOrderView order = orderView("PREAUTH-H5-001", "AUTH-001");
        when(orderService.recordAlipayNotify(
                eq("PREAUTH-H5-001"),
                eq("AUTH-001"),
                eq("ali-main"),
                eq(new BigDecimal("1.00")),
                eq("TRADE_SUCCESS")
        )).thenReturn(order);

        AlipayNotifyController controller = new AlipayNotifyController(
                channelRegistry,
                openApiClient,
                orderService,
                merchantNotifyService
        );

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("out_order_no", "PREAUTH-H5-001");
        form.add("auth_no", "AUTH-001");
        form.add("trade_no", "TRADE-IGNORED");
        form.add("amount", "1.00");
        form.add("status", "SUCCESS");

        controller.notify("ali-main", form);

        verify(orderService).recordAlipayNotify(
                "PREAUTH-H5-001",
                "AUTH-001",
                "ali-main",
                new BigDecimal("1.00"),
                "TRADE_SUCCESS"
        );
        verify(merchantNotifyService).notifyPayment(order, "TRADE_SUCCESS");
    }

    @Test
    void normalTradeNotifyKeepsTradeNoFirst() {
        ChannelRegistry channelRegistry = mock(ChannelRegistry.class);
        AlipayOpenApiClient openApiClient = mock(AlipayOpenApiClient.class);
        DemoOrderService orderService = mock(DemoOrderService.class);
        MerchantNotifyService merchantNotifyService = mock(MerchantNotifyService.class);
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-main");
        when(channelRegistry.find("ali-main")).thenReturn(Optional.of(channel));
        when(openApiClient.verifyNotify(eq(channel), anyMap())).thenReturn(true);
        DemoOrderView order = orderView("PAY-001", "TRADE-001");
        when(orderService.recordAlipayNotify(
                eq("PAY-001"),
                eq("TRADE-001"),
                eq("ali-main"),
                eq(new BigDecimal("2.00")),
                eq("TRADE_SUCCESS")
        )).thenReturn(order);

        AlipayNotifyController controller = new AlipayNotifyController(
                channelRegistry,
                openApiClient,
                orderService,
                merchantNotifyService
        );

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("out_trade_no", "PAY-001");
        form.add("auth_no", "AUTH-SHOULD-NOT-WIN");
        form.add("trade_no", "TRADE-001");
        form.add("total_amount", "2.00");
        form.add("trade_status", "TRADE_SUCCESS");

        controller.notify("ali-main", form);

        verify(orderService).recordAlipayNotify(
                "PAY-001",
                "TRADE-001",
                "ali-main",
                new BigDecimal("2.00"),
                "TRADE_SUCCESS"
        );
    }

    private static DemoOrderView orderView(String outTradeNo, String tradeNo) {
        return new DemoOrderView(
                outTradeNo,
                tradeNo,
                "ali-main",
                "M10001",
                "测试商户",
                "预授权",
                "扫码收银台支付",
                new BigDecimal("1.00"),
                "¥1.00",
                BigDecimal.ZERO,
                "¥0.00",
                new BigDecimal("1.00"),
                "¥1.00",
                BigDecimal.ZERO,
                "¥0.00",
                new BigDecimal("1.00"),
                "¥1.00",
                DemoOrderStatus.FROZEN,
                "冻结中",
                "2026-06-15 20:30:00",
                true,
                false,
                false
        );
    }
}
