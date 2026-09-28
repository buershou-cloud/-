package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.api.MerchantApiException;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.sharing.ProfitSharingRelationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCallbackRoutingTest {
    @Test
    void merchantAlipayPaymentUsesGatewayCallbackAndStoresOriginalMerchantTarget() {
        Fixture f = fixture("https://gateway.example/api/v1/alipay/notify/ali");
        PayCreateRequest original = request(Map.of("merchantId", "M1", "merchantApiRequest", true));

        GatewayResponse result = f.service.pay(original);

        ArgumentCaptor<PayCreateRequest> captured = ArgumentCaptor.forClass(PayCreateRequest.class);
        verify(f.provider).pay(eq(f.channel), captured.capture());
        assertThat(captured.getValue().notifyUrl()).isEqualTo("https://gateway.example/api/v1/alipay/notify/ali");
        assertThat(captured.getValue().extra()).doesNotContainKey("merchantApiRequest");
        assertThat(original.notifyUrl()).isEqualTo("https://merchant.example/payment-notify");
        assertThat(original.extra()).containsEntry("merchantApiRequest", true);
        verify(f.orders).recordPaymentMetadata("ORDER", original, result);
    }

    @Test
    void regularAlipayRequestRetainsItsNotifyUrlAndExactPayload() {
        Fixture f = fixture("https://gateway.example/api/v1/alipay/notify/ali");
        PayCreateRequest original = request(Map.of());

        f.service.pay(original);

        verify(f.provider).pay(f.channel, original);
    }

    @Test
    void merchantPaymentWithoutConfiguredGatewayCallbackFailsBeforeUpstreamCall() {
        Fixture f = fixture(null);

        GatewayResponse result = f.service.pay(request(Map.of("merchantId", "M1", "merchantApiRequest", true)));

        assertThat(result.code()).isEqualTo("MERCHANT_GATEWAY_NOTIFY_URL_MISSING");
        verify(f.provider, never()).pay(any(), any());
        verify(f.orders, never()).recordPaymentMetadata(any(), any(), any());
    }

    @Test
    void publicCashierReservesBeforeCallingProviderWithoutChangingCallback() {
        Fixture f = fixture("https://gateway.example/api/v1/alipay/notify/ali");
        PayCreateRequest original = request(Map.of("cashier", true));

        f.service.payPublic(original);

        var sequence = inOrder(f.orders, f.provider);
        sequence.verify(f.orders).reserveMerchantPayment("M10001", "默认商户", "ali", original);
        sequence.verify(f.provider).pay(f.channel, original);
    }

    @Test
    void concurrentReservationConflictStopsPublicPaymentBeforeProvider() {
        Fixture f = fixture("https://gateway.example/api/v1/alipay/notify/ali");
        doThrow(new MerchantApiException("ORDER_CONFLICT", "Already reserved"))
                .when(f.orders).reserveMerchantPayment(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> f.service.payPublic(request(Map.of()))).isInstanceOf(MerchantApiException.class);

        verify(f.provider, never()).pay(any(), any());
    }

    @Test
    void publicPaymentTimeoutNeverFailsOverToAnotherChannel() {
        Fixture f = fixture("https://gateway.example/api/v1/alipay/notify/ali");
        PaymentGatewayProperties.Channel backup = new PaymentGatewayProperties.Channel();
        backup.setId("backup");
        backup.setProvider("ALIPAY");
        backup.setEnabled(true);
        backup.setDailyEnabled(true);
        when(f.registry.all()).thenReturn(List.of(f.channel, backup));
        when(f.registry.isEnabled(backup)).thenReturn(true);
        f.properties.getRouting().setFailover(true);
        f.properties.getRouting().setMaxAttempts(3);
        when(f.provider.pay(any(), any())).thenThrow(new GatewayException("TIMEOUT", "Unknown result"));

        GatewayResponse result = f.service.payPublic(request(Map.of(), List.of("ali", "backup")));

        assertThat(result.code()).isEqualTo("TIMEOUT");
        assertThat(result.attempts()).hasSize(1);
        verify(f.provider, never()).pay(eq(backup), any());
    }

    private static Fixture fixture(String notifyUrl) {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.getRouting().setMaxAttempts(1);
        properties.getRouting().setFailover(false);
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali");
        channel.setProvider("ALIPAY");
        channel.setEnabled(true);
        channel.setDailyEnabled(true);
        channel.getAlipay().setNotifyUrl(notifyUrl);
        ChannelRegistry registry = mock(ChannelRegistry.class);
        when(registry.all()).thenReturn(List.of(channel));
        when(registry.isEnabled(channel)).thenReturn(true);
        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.providerCode()).thenReturn("ALIPAY");
        when(provider.pay(any(), any())).thenReturn(new GatewayResponse("ali", PaymentStatus.CREATED, "10000", "Created",
                "ORDER", "TRADE", null, null, Map.of(), List.of()));
        DemoOrderService orders = mock(DemoOrderService.class);
        PaymentGatewayService service = new PaymentGatewayService(properties, new ChannelSelector(registry), List.of(provider),
                orders, mock(DemoMerchantService.class), mock(OnboardingRecordService.class), mock(ComplaintRecordService.class),
                mock(ProfitSharingRelationService.class));
        return new Fixture(service, channel, provider, orders, properties, registry);
    }

    private static PayCreateRequest request(Map<String, Object> extra) {
        return request(extra, List.of("ali"));
    }

    private static PayCreateRequest request(Map<String, Object> extra, List<String> channelIds) {
        return new PayCreateRequest(PaymentProduct.ALIPAY_F2F, "ORDER", "Subject", new BigDecimal("1.00"), null, null, null,
                null, null, "https://merchant.example/payment-notify", null, null, null, channelIds, extra, null, null);
    }

    private record Fixture(PaymentGatewayService service, PaymentGatewayProperties.Channel channel,
                           PaymentProvider provider, DemoOrderService orders, PaymentGatewayProperties properties,
                           ChannelRegistry registry) { }
}
