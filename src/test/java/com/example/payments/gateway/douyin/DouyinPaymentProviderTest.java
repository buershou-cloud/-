package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.RefundCreateRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DouyinPaymentProviderTest {

    @Test
    void createsOfficialH5OrderAndReturnsRedirectUrl() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.post(any(), eq("/v1/trade/transactions/h5"), anyMap())).thenReturn(new DouyinGatewayResponse(
                200,
                Map.of("h5_url", "https://cashier.douyinpay.com/h5/demo", "transaction_id", "DY1001"),
                "{}",
                Map.of()
        ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();

        GatewayResponse response = provider.pay(channel, new PayCreateRequest(
                PaymentProduct.DOUYIN_H5,
                "ORDER-1001",
                "测试商品",
                new BigDecimal("1.23"),
                null,
                null,
                null,
                null,
                "10m",
                "https://incorrect.example.com/alipay/notify",
                "https://merchant.example.com/return",
                null,
                null,
                List.of("douyin-test"),
                Map.of("payer_client_ip", "203.0.113.8", "user_agent", "test-agent"),
                null,
                null
        ));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.redirectUrl()).isEqualTo("https://cashier.douyinpay.com/h5/demo");
        assertThat(response.tradeNo()).isEqualTo("DY1001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(channel), eq("/v1/trade/transactions/h5"), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body)
                .containsEntry("appid", "dy-app-1")
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("out_trade_no", "ORDER-1001")
                .containsEntry("notify_url", "https://merchant.example.com/api/v1/douyin/notify/douyin-test");
        assertThat((Map<String, Object>) body.get("amount"))
                .containsEntry("total", 123L)
                .containsEntry("currency", "CNY");
        Map<String, Object> sceneInfo = (Map<String, Object>) body.get("scene_info");
        assertThat(sceneInfo)
                .containsEntry("payer_client_ip", "203.0.113.8")
                .containsEntry("user_agent", "test-agent");
        assertThat((Map<String, Object>) sceneInfo.get("h5_info"))
                .containsEntry("type", "Wap")
                .containsEntry("app_name", "支付平台")
                .containsEntry("app_url", "https://merchant.example.com/return");
    }

    @Test
    void mapsDouyinQueryStateToGatewayStatus() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.get(any(), eq("/v1/trade/transactions/out-trade-no/ORDER-1001?mchid=dy-mch-1")))
                .thenReturn(new DouyinGatewayResponse(
                        200,
                        Map.of(
                                "trade_state", "SUCCESS",
                                "out_trade_no", "ORDER-1001",
                                "transaction_id", "DY1001"
                        ),
                        "{}",
                        Map.of()
                ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);

        GatewayResponse response = provider.query(
                channel(),
                new PaymentQueryRequest("ORDER-1001", null, null, List.of("douyin-test"), Map.of())
        );

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.tradeNo()).isEqualTo("DY1001");
    }

    @Test
    void submitsOfficialRefundRequestWithFenAmountsAndCallback() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.post(any(), eq("/v1/trade/refund/domestic/refunds"), anyMap()))
                .thenReturn(new DouyinGatewayResponse(
                        200,
                        Map.of(
                                "status", "PROCESSING",
                                "out_trade_no", "ORDER-1001",
                                "transaction_id", "DY1001"
                        ),
                        "{}",
                        Map.of()
                ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();

        GatewayResponse response = provider.refund(channel, new RefundCreateRequest(
                "ORDER-1001",
                "DY1001",
                new BigDecimal("1.23"),
                "REFUND-1001",
                "测试退款",
                null,
                List.of("douyin-test"),
                Map.of("douyin_total_amount_fen", 500L)
        ));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.outTradeNo()).isEqualTo("ORDER-1001");
        assertThat(response.tradeNo()).isEqualTo("DY1001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(channel), eq("/v1/trade/refund/domestic/refunds"), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body)
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("transaction_id", "DY1001")
                .containsEntry("out_trade_no", "ORDER-1001")
                .containsEntry("out_refund_no", "REFUND-1001")
                .containsEntry("reason", "测试退款")
                .containsEntry("notify_url", "https://merchant.example.com/api/v1/douyin/notify/douyin-test");
        assertThat((Map<String, Object>) body.get("amount"))
                .containsEntry("refund", 123L)
                .containsEntry("total", 500L)
                .containsEntry("currency", "CNY");
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("douyin-test");
        channel.setProvider("DOUYIN");
        channel.setProducts(Set.of(PaymentProduct.DOUYIN_H5));
        channel.getDouyin().setAppId("dy-app-1");
        channel.getDouyin().setMchId("dy-mch-1");
        channel.getDouyin().setNotifyUrl("https://merchant.example.com/api/v1/douyin/notify/douyin-test");
        channel.getDouyin().setReturnUrl("https://merchant.example.com/return");
        channel.getDouyin().setH5AppName("支付平台");
        return channel;
    }
}
