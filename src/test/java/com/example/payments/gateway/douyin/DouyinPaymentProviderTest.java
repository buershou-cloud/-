package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.domain.ProfitSharingFinishRequest;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRelationQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
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
                Map.of(
                        "payer_client_ip", "203.0.113.8",
                        "user_agent", "test-agent",
                        "time_expire", "2026-07-20T12:30:00+08:00",
                        "support_fapiao", true
                ),
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
                .containsEntry("notify_url", "https://merchant.example.com/api/v1/douyin/notify/douyin-test")
                .containsEntry("time_expire", "2026-07-20T12:30:00+08:00")
                .containsEntry("support_fapiao", true)
                .doesNotContainKey("profit_sharing");
        assertThat((Map<String, Object>) body.get("settle_info"))
                .containsEntry("profit_sharing", true);
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
    void createsOfficialNativeOrderAndReturnsCodeUrl() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.post(any(), eq("/v1/trade/transactions/native"), anyMap())).thenReturn(new DouyinGatewayResponse(
                200,
                Map.of("code_url", "https://qr.douyinpay.com/native/demo"),
                "{}",
                Map.of()
        ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();

        GatewayResponse response = provider.pay(channel, new PayCreateRequest(
                PaymentProduct.DOUYIN_NATIVE,
                "NATIVE-1001",
                "扫码收银台支付",
                new BigDecimal("12.34"),
                null,
                null,
                null,
                null,
                "10m",
                null,
                null,
                null,
                null,
                List.of("douyin-test"),
                Map.of("time_expire", "2026-07-19T12:30:00+08:00", "attach", "native-order"),
                Map.of("profit_sharing", false),
                null
        ));

        assertThat(provider.supports(channel, PaymentProduct.DOUYIN_NATIVE)).isTrue();
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.qrCode()).isEqualTo("https://qr.douyinpay.com/native/demo");
        assertThat(response.redirectUrl()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(channel), eq("/v1/trade/transactions/native"), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body)
                .containsEntry("appid", "dy-app-1")
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("description", "扫码收银台支付")
                .containsEntry("out_trade_no", "NATIVE-1001")
                .containsEntry("notify_url", "https://merchant.example.com/api/v1/douyin/notify/douyin-test")
                .containsEntry("time_expire", "2026-07-19T12:30:00+08:00")
                .containsEntry("attach", "native-order")
                .doesNotContainKey("profit_sharing");
        assertThat((Map<String, Object>) body.get("amount"))
                .containsEntry("total", 1234L)
                .containsEntry("currency", "CNY");
        assertThat((Map<String, Object>) body.get("settle_info"))
                .containsEntry("profit_sharing", false);
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
                .containsEntry("appid", "dy-app-1")
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

    @Test
    void mapsDouyinPayErrorToFailedStatus() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.get(any(), eq("/v1/trade/transactions/out-trade-no/ORDER-FAILED?mchid=dy-mch-1")))
                .thenReturn(new DouyinGatewayResponse(
                        200,
                        Map.of("trade_state", "PAYERROR", "out_trade_no", "ORDER-FAILED"),
                        "{}",
                        Map.of()
                ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);

        GatewayResponse response = provider.query(
                channel(),
                new PaymentQueryRequest("ORDER-FAILED", null, null, List.of("douyin-test"), Map.of())
        );

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void submitsOfficialProfitSharingRequestWithFenReceiverAmount() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.postSensitive(any(), eq("/v1/trade/profitsharing/orders"), anyMap()))
                .thenReturn(new DouyinGatewayResponse(
                        200,
                        Map.of(
                                "state", "PROCESSING",
                                "out_order_no", "PS_ORDER-1001",
                                "order_id", "DYPS1001",
                                "transaction_id", "DY1001"
                        ),
                        "{}",
                        Map.of()
                ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();

        GatewayResponse response = provider.profitSharing(channel, new ProfitSharingRequest(
                "ORDER-1001",
                "DY1001",
                "PS_ORDER-1001",
                List.of(Map.of(
                        "trans_in_type", "MERCHANT_ID",
                        "trans_in", "dy-receiver-mch",
                        "amount", new BigDecimal("0.50"),
                        "desc", "合作方分账"
                )),
                null,
                null,
                List.of("douyin-test"),
                Map.of("unfreeze_unsplit", false)
        ));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.tradeNo()).isEqualTo("DY1001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).postSensitive(eq(channel), eq("/v1/trade/profitsharing/orders"), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body)
                .containsEntry("appid", "dy-app-1")
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("transaction_id", "DY1001")
                .containsEntry("out_order_no", "PS_ORDER-1001")
                .containsEntry("unfreeze_unsplit", false);
        List<Map<String, Object>> receivers = (List<Map<String, Object>>) body.get("receivers");
        assertThat(receivers).singleElement().satisfies(receiver -> assertThat(receiver)
                .containsEntry("type", "MERCHANT_ID")
                .containsEntry("account", "dy-receiver-mch")
                .containsEntry("amount", 50L)
                .containsEntry("description", "合作方分账"));
    }

    @Test
    void addsAndDeletesOfficialProfitSharingReceiver() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        when(client.postSensitive(any(), eq("/v1/trade/profitsharing/receivers/add"), anyMap()))
                .thenReturn(new DouyinGatewayResponse(204, Map.of(), "", Map.of()));
        when(client.post(any(), eq("/v1/trade/profitsharing/receivers/delete"), anyMap()))
                .thenReturn(new DouyinGatewayResponse(204, Map.of(), "", Map.of()));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();
        ProfitSharingRelationBindRequest request = new ProfitSharingRelationBindRequest(
                "receiver-open-id",
                "PERSONAL_OPENID",
                null,
                "partner",
                "REL-1001",
                null,
                List.of("douyin-test"),
                Map.of("relation_type", "PARTNER")
        );

        GatewayResponse added = provider.bindProfitSharingRelation(channel, request);
        GatewayResponse deleted = provider.unbindProfitSharingRelation(channel, request);

        assertThat(added.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(deleted.status()).isEqualTo(PaymentStatus.SUCCESS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> addBody = ArgumentCaptor.forClass(Map.class);
        verify(client).postSensitive(eq(channel), eq("/v1/trade/profitsharing/receivers/add"), addBody.capture());
        assertThat(addBody.getValue())
                .containsEntry("appid", "dy-app-1")
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("type", "PERSONAL_OPENID")
                .containsEntry("account", "receiver-open-id")
                .containsEntry("relation_type", "PARTNER");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> deleteBody = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(channel), eq("/v1/trade/profitsharing/receivers/delete"), deleteBody.capture());
        assertThat(deleteBody.getValue())
                .containsEntry("appid", "dy-app-1")
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("type", "PERSONAL_OPENID")
                .containsEntry("account", "receiver-open-id");
    }

    @Test
    void queriesAndFinishesOfficialProfitSharingOrder() {
        DouyinPayClient client = mock(DouyinPayClient.class);
        String queryPath = "/v1/trade/profitsharing/orders/PS_ORDER-1001?mchid=dy-mch-1&transaction_id=DY1001";
        when(client.get(any(), eq(queryPath))).thenReturn(new DouyinGatewayResponse(
                200,
                Map.of("state", "SUCCESS", "out_order_no", "PS_ORDER-1001", "transaction_id", "DY1001"),
                "{}",
                Map.of()
        ));
        when(client.post(any(), eq("/v1/trade/profitsharing/finish-orders"), anyMap()))
                .thenReturn(new DouyinGatewayResponse(
                        200,
                        Map.of("state", "PROCESSING", "out_order_no", "PS_FINISH-1001", "transaction_id", "DY1001"),
                        "{}",
                        Map.of()
                ));
        DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
        PaymentGatewayProperties.Channel channel = channel();

        GatewayResponse queried = provider.queryProfitSharing(channel, new ProfitSharingQueryRequest(
                "ORDER-1001", "DY1001", "PS_ORDER-1001", null, List.of("douyin-test"), Map.of()
        ));
        GatewayResponse finished = provider.finishProfitSharing(channel, new ProfitSharingFinishRequest(
                "ORDER-1001", "DY1001", "PS_FINISH-1001", "finish sharing", List.of("douyin-test"), Map.of()
        ));

        assertThat(queried.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(finished.status()).isEqualTo(PaymentStatus.PENDING);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> finishBody = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq(channel), eq("/v1/trade/profitsharing/finish-orders"), finishBody.capture());
        assertThat(finishBody.getValue())
                .containsEntry("mchid", "dy-mch-1")
                .containsEntry("transaction_id", "DY1001")
                .containsEntry("out_order_no", "PS_FINISH-1001")
                .containsEntry("description", "finish sharing");
    }

    @Test
    void relationRefreshDoesNotInventRemoteReceiverRecords() {
        DouyinPaymentProvider provider = new DouyinPaymentProvider(mock(DouyinPayClient.class));

        GatewayResponse response = provider.queryProfitSharingRelations(channel(), new ProfitSharingRelationQueryRequest(
                null, null, null, null, null, null, List.of("douyin-test"), Map.of()
        ));

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(response.raw()).containsEntry("provider", "DOUYIN");
        assertThat(response.raw()).doesNotContainKeys("account", "type", "status");
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("douyin-test");
        channel.setProvider("DOUYIN");
        channel.setProducts(Set.of(PaymentProduct.DOUYIN_H5, PaymentProduct.DOUYIN_NATIVE));
        channel.getDouyin().setAppId("dy-app-1");
        channel.getDouyin().setMchId("dy-mch-1");
        channel.getDouyin().setNotifyUrl("https://merchant.example.com/api/v1/douyin/notify/douyin-test");
        channel.getDouyin().setReturnUrl("https://merchant.example.com/return");
        channel.getDouyin().setH5AppName("支付平台");
        return channel;
    }
}
