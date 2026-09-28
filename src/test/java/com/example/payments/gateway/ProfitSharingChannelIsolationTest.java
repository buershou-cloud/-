package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingBatchRequest;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.ProfitSharingReturnRequest;
import com.example.payments.domain.RoutingMode;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.example.payments.sharing.ProfitSharingRelationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfitSharingChannelIsolationTest {

    @Test
    void partialFailureRetainsProviderDetailsAndDoesNotMarkPaid() {
        Fixture f = fixture();
        Map<String, Object> raw = Map.of("profit_sharing_out_order_no", "PS-ORDER", "receivers",
                List.of(Map.of("result", "SUCCESS"), Map.of("result", "CLOSED", "fail_reason", "ACCOUNT_ABNORMAL")));
        GatewayResponse failed = new GatewayResponse("douyin", PaymentStatus.FAILED, "FINISHED", "部分失败",
                "ORDER", "DY-TRADE", null, null, raw, List.of());
        when(f.douyin.profitSharing(any(), any())).thenReturn(failed);

        GatewayResponse result = f.service.profitSharing(request("ORDER", "DY-TRADE", "MERCHANT_ID", List.of("douyin")));

        assertThat(result.raw()).isEqualTo(raw);
        assertThat(result.channelId()).isEqualTo("douyin");
        assertThat(result.tradeNo()).isEqualTo("DY-TRADE");
        assertThat(result.attempts()).hasSize(1);
        verify(f.orders, never()).markProfitShared(anyString());
        verify(f.alipay, never()).profitSharing(any(), any());
    }

    @Test
    void completedUnfreezeQueryNeverMarksOrderAsDistributed() {
        Fixture f = fixture();
        when(f.douyin.queryProfitSharing(any(), any())).thenReturn(new GatewayResponse("douyin", PaymentStatus.SUCCESS,
                "FINISHED", "解冻完成", "ORDER", "DY-TRADE", null, null,
                Map.of("profit_sharing_operation", "FINISH"), List.of()));

        assertThat(f.service.queryProfitSharing(new ProfitSharingQueryRequest(
                "ORDER", "DY-TRADE", "FINISH_1001", null, List.of("douyin"), Map.of())).status())
                .isEqualTo(PaymentStatus.SUCCESS);

        verify(f.orders, never()).markProfitShared(anyString());
    }

    @Test
    void addingDouyinKeepsImplicitAlipayRequestUnchanged() {
        Fixture f = fixture();
        ProfitSharingRequest request = request("ORDER", "TRADE", "loginName", null);

        assertThat(f.service.profitSharing(request).status()).isEqualTo(PaymentStatus.SUCCESS);

        verify(f.alipay).profitSharing(f.ali, request);
        verify(f.douyin, never()).profitSharing(any(), any());
    }

    @Test
    void alipayFailureDoesNotFailOverIntoNewDouyinChannel() {
        Fixture f = fixture();
        when(f.alipay.profitSharing(any(), any())).thenReturn(response("ali", PaymentStatus.FAILED));

        assertThat(f.service.profitSharing(request("ORDER", "TRADE", "loginName", null)).status())
                .isEqualTo(PaymentStatus.FAILED);

        verify(f.douyin, never()).profitSharing(any(), any());
    }

    @Test
    void resolvesLocalDouyinChannelAndTransactionWithoutUsingAlipay() {
        Fixture f = fixture();
        localOrder(f, "douyin", "DY-TRADE");

        f.service.profitSharing(request("ORDER", null, "MERCHANT_ID", null));

        ArgumentCaptor<ProfitSharingRequest> captor = ArgumentCaptor.forClass(ProfitSharingRequest.class);
        verify(f.douyin).profitSharing(eq(f.dy), captor.capture());
        assertThat(captor.getValue().tradeNo()).isEqualTo("DY-TRADE");
        assertThat(captor.getValue().outRequestNo()).isEqualTo("PS-ORDER");
        assertThat(captor.getValue().royaltyParameters().getFirst()).containsEntry("amount", "1.25");
        verify(f.alipay, never()).profitSharing(any(), any());
    }

    @Test
    void rejectsMixedChannelsBeforeAnyRemoteSplit() {
        Fixture f = fixture();

        assertThatThrownBy(() -> f.service.profitSharing(
                request(null, "TRADE", "MERCHANT_ID", List.of("douyin", "ali"))))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.code()).isEqualTo("DOUYIN_PROFIT_SHARING_CHANNEL_REQUIRED"));

        verify(f.douyin, never()).profitSharing(any(), any());
        verify(f.alipay, never()).profitSharing(any(), any());
    }

    @Test
    void douyinFailureNeverRetriesAlipay() {
        Fixture f = fixture();
        when(f.douyin.profitSharing(any(), any())).thenThrow(new GatewayException("TIMEOUT", "Unknown remote result"));

        GatewayResponse response = f.service.profitSharing(request(null, "TRADE", "MERCHANT_ID", List.of("douyin")));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.attempts()).hasSize(1);
        verify(f.alipay, never()).profitSharing(any(), any());
    }

    @Test
    void rejectsDouyinReceiverWithoutChannelInsteadOfSendingItToAlipay() {
        Fixture f = fixture();

        assertThatThrownBy(() -> f.service.profitSharing(request(null, "TRADE", "MERCHANT_ID", null)))
                .isInstanceOf(GatewayException.class);

        verify(f.alipay, never()).profitSharing(any(), any());
        verify(f.douyin, never()).profitSharing(any(), any());
    }

    @Test
    void rejectsLocalDouyinOrderWhenCallerSelectsAlipay() {
        Fixture f = fixture();
        localOrder(f, "douyin", "DY-TRADE");

        assertThatThrownBy(() -> f.service.profitSharing(request("ORDER", "DY-TRADE", "MERCHANT_ID", List.of("ali"))))
                .isInstanceOfSatisfying(GatewayException.class,
                        ex -> assertThat(ex.code()).isEqualTo("DOUYIN_PROFIT_SHARING_CHANNEL_MISMATCH"));

        verify(f.alipay, never()).profitSharing(any(), any());
    }

    @Test
    void rejectsAlipayOrderPassedToDouyinBeforeRemoteCall() {
        Fixture f = fixture();
        localOrder(f, "ali", "ALI-TRADE");

        GatewayResponse response = f.service.profitSharing(request("ORDER", "ALI-TRADE", "MERCHANT_ID", List.of("douyin")));

        assertThat(response.code()).isEqualTo("DOUYIN_PROFIT_SHARING_CHANNEL_MISMATCH");
        verify(f.douyin, never()).profitSharing(any(), any());
        verify(f.orders, never()).markProfitShared(anyString());
    }

    @Test
    void rejectsDifferentDouyinTransactionBeforeRemoteCall() {
        Fixture f = fixture();
        localOrder(f, "douyin", "DY-TRADE");

        GatewayResponse response = f.service.profitSharing(request("ORDER", "OTHER-TRADE", "MERCHANT_ID", List.of("douyin")));

        assertThat(response.code()).isEqualTo("DOUYIN_PROFIT_SHARING_TRADE_MISMATCH");
        verify(f.douyin, never()).profitSharing(any(), any());
    }

    @Test
    void successfulExternalDouyinSplitSurvivesMissingLocalOrder() {
        Fixture f = fixture();
        when(f.orders.view("EXTERNAL")).thenThrow(new IllegalArgumentException("Order does not exist"));
        when(f.orders.markProfitShared("EXTERNAL")).thenThrow(new IllegalArgumentException("Order does not exist"));

        GatewayResponse response = f.service.profitSharing(request("EXTERNAL", "TRADE", "MERCHANT_ID", List.of("douyin")));

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void validatesCanonicalDouyinReceiverWithinSelectedChannel() {
        Fixture f = fixture();
        when(f.relations.isBound("douyin", "MERCHANT_ID", "receiver")).thenReturn(false);

        GatewayResponse response = f.service.profitSharing(request(null, "TRADE", "merchant_id", List.of("douyin")));

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        verify(f.relations).isBound("douyin", "MERCHANT_ID", "receiver");
        verify(f.douyin, never()).profitSharing(any(), any());
    }

    @Test
    void queryResolvesLocalTransactionAndPendingResultDoesNotMarkShared() {
        Fixture f = fixture();
        localOrder(f, "douyin", "DY-TRADE");
        when(f.douyin.queryProfitSharing(any(), any())).thenReturn(response("douyin", PaymentStatus.PENDING));

        f.service.queryProfitSharing(new ProfitSharingQueryRequest("ORDER", null, "PS-ORDER", null, null, Map.of()));

        ArgumentCaptor<ProfitSharingQueryRequest> captor = ArgumentCaptor.forClass(ProfitSharingQueryRequest.class);
        verify(f.douyin).queryProfitSharing(eq(f.dy), captor.capture());
        assertThat(captor.getValue().tradeNo()).isEqualTo("DY-TRADE");
        verify(f.orders, never()).markProfitShared(anyString());
        verify(f.alipay, never()).queryProfitSharing(any(), any());
    }

    @Test
    void returnAmountUsesSharingRulesInsteadOfPaymentMinimum() {
        Fixture f = fixture();
        f.dy.setPayMin(new BigDecimal("10.00"));
        when(f.douyin.returnProfitSharing(any(), any())).thenReturn(response("douyin", PaymentStatus.PENDING));
        ProfitSharingReturnRequest request = new ProfitSharingReturnRequest(
                "PS-ORDER", "RETURN-1", "receiver", new BigDecimal("0.01"), null, List.of("douyin"), Map.of());

        assertThat(f.service.returnProfitSharing(request).status()).isEqualTo(PaymentStatus.PENDING);

        verify(f.douyin).returnProfitSharing(f.dy, request);
    }

    @Test
    void fillsMerchantNameFromBoundRelationWithoutMutatingCallerReceiverMap() {
        Fixture f = fixture();
        merchantRelation(f);
        ProfitSharingRequest request = request(null, "TRADE", "MERCHANT_ID", List.of("douyin"));

        f.service.profitSharing(request);

        ArgumentCaptor<ProfitSharingRequest> captor = ArgumentCaptor.forClass(ProfitSharingRequest.class);
        verify(f.douyin).profitSharing(eq(f.dy), captor.capture());
        assertThat(captor.getValue().royaltyParameters().getFirst()).containsEntry("receiver_name", "商户名称");
        assertThat(request.royaltyParameters().getFirst()).doesNotContainKey("receiver_name");
    }

    @Test
    void channelBatchUsesMerchantNameFromItsOwnBoundRelation() {
        Fixture f = fixture();
        merchantRelation(f);
        localOrder(f, "douyin", "DY-TRADE");
        DemoOrderView order = f.orders.view("ORDER");
        when(order.outTradeNo()).thenReturn("ORDER");
        when(order.amount()).thenReturn(new BigDecimal("2.00"));
        when(f.orders.shareableByChannel("douyin", false)).thenReturn(List.of(order));
        ProfitSharingBatchRequest request = new ProfitSharingBatchRequest("douyin", "receiver", "MERCHANT_ID",
                new BigDecimal("1.25"), null, null, "BATCH", null, null, false, Map.of());

        assertThat(f.service.profitSharingByChannel(request).failed()).isZero();

        ArgumentCaptor<ProfitSharingRequest> captor = ArgumentCaptor.forClass(ProfitSharingRequest.class);
        verify(f.douyin).profitSharing(eq(f.dy), captor.capture());
        assertThat(captor.getValue().outRequestNo()).isEqualTo("BATCH_ORDER");
        assertThat(captor.getValue().royaltyParameters().getFirst())
                .containsEntry("receiver_name", "商户名称").containsEntry("amount", "1.25");
    }

    private static void merchantRelation(Fixture f) {
        when(f.relations.list("douyin")).thenReturn(List.of(new ProfitSharingRelationService.ProfitSharingRelationView(
                "douyin", "MERCHANT_ID", "receiver", "商户名称", null, "REL-1", "BOUND", null)));
    }

    private static Fixture fixture() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.getRouting().setMaxAttempts(3);
        properties.getRouting().setFailover(true);
        properties.getRouting().setMode(RoutingMode.PRIORITY);
        PaymentGatewayProperties.Channel ali = channel("ali", "ALIPAY");
        PaymentGatewayProperties.Channel dy = channel("douyin", "DOUYIN");
        ChannelRegistry registry = mock(ChannelRegistry.class);
        when(registry.all()).thenReturn(List.of(ali, dy));
        when(registry.isEnabled(any())).thenReturn(true);
        when(registry.find("ali")).thenReturn(Optional.of(ali));
        when(registry.find("douyin")).thenReturn(Optional.of(dy));
        PaymentProvider alipay = mock(PaymentProvider.class);
        PaymentProvider douyin = mock(PaymentProvider.class);
        when(alipay.providerCode()).thenReturn("ALIPAY");
        when(douyin.providerCode()).thenReturn("DOUYIN");
        when(alipay.profitSharing(any(), any())).thenReturn(response("ali", PaymentStatus.SUCCESS));
        when(douyin.profitSharing(any(), any())).thenReturn(response("douyin", PaymentStatus.SUCCESS));
        DemoOrderService orders = mock(DemoOrderService.class);
        ProfitSharingRelationService relations = mock(ProfitSharingRelationService.class);
        when(relations.isBound(anyString(), anyString(), anyString())).thenReturn(true);
        PaymentGatewayService service = new PaymentGatewayService(properties, new ChannelSelector(registry),
                List.of(alipay, douyin), orders, mock(DemoMerchantService.class),
                mock(OnboardingRecordService.class), mock(ComplaintRecordService.class), relations,
                mock(com.example.payments.sharing.ProfitSharingRecordService.class));
        return new Fixture(service, ali, dy, alipay, douyin, orders, relations);
    }

    private static PaymentGatewayProperties.Channel channel(String id, String provider) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId(id);
        channel.setProvider(provider);
        channel.setEnabled(true);
        channel.setDailyEnabled(true);
        return channel;
    }

    private static void localOrder(Fixture f, String channel, String tradeNo) {
        DemoOrderView order = mock(DemoOrderView.class);
        when(order.channelId()).thenReturn(channel);
        when(order.tradeNo()).thenReturn(tradeNo);
        when(f.orders.view("ORDER")).thenReturn(order);
    }

    private static ProfitSharingRequest request(String order, String trade, String type, List<String> channels) {
        return new ProfitSharingRequest(order, trade, "PS-ORDER",
                List.of(Map.of("trans_in_type", type, "trans_in", "receiver", "amount", "1.25")),
                null, null, channels, Map.of("unfreeze_unsplit", false));
    }

    private static GatewayResponse response(String channel, PaymentStatus status) {
        return new GatewayResponse(channel, status, "RESULT", "Result", "ORDER", "TRADE", null, null, Map.of(), List.of());
    }

    private record Fixture(PaymentGatewayService service, PaymentGatewayProperties.Channel ali,
                           PaymentGatewayProperties.Channel dy, PaymentProvider alipay, PaymentProvider douyin,
                           DemoOrderService orders, ProfitSharingRelationService relations) { }
}
