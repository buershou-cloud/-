package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RoutingMode;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.sharing.ProfitSharingRecordException;
import com.example.payments.sharing.ProfitSharingRecordService;
import com.example.payments.sharing.ProfitSharingRelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfitSharingRecordingGatewayTest {
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final ProfitSharingRecordService records = mock(ProfitSharingRecordService.class);
    private final PaymentGatewayProperties.Channel first = channel("first", 1);
    private final PaymentGatewayProperties.Channel second = channel("second", 2);
    private PaymentGatewayService gateway;
    private final ProfitSharingRequest request = new ProfitSharingRequest("ORDER", "TRADE", "SPLIT",
            List.of(Map.of("trans_in_type", "loginName", "trans_in", "receiver", "amount", "1.00")), null, null, null, Map.of());
    private final GatewayResponse success = new GatewayResponse("first", PaymentStatus.SUCCESS, "10000", "Success",
            "ORDER", "TRADE", null, null, Map.of(), List.of());

    @BeforeEach
    void setup() {
        gateway = createGateway(records);
    }

    private PaymentGatewayService createGateway(ProfitSharingRecordService recordService) {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.getRouting().setFailover(true);
        properties.getRouting().setMaxAttempts(3);
        properties.getRouting().setMode(RoutingMode.PRIORITY);
        ChannelRegistry registry = mock(ChannelRegistry.class);
        when(registry.all()).thenReturn(List.of(first, second));
        when(registry.isEnabled(any())).thenReturn(true);
        when(registry.find("first")).thenReturn(Optional.of(first));
        when(registry.find("second")).thenReturn(Optional.of(second));
        when(provider.providerCode()).thenReturn("ALIPAY");
        when(provider.profitSharing(any(), any())).thenReturn(success);
        ProfitSharingRelationService relations = mock(ProfitSharingRelationService.class);
        when(relations.isBound(anyString(), anyString(), anyString())).thenReturn(true);
        return new PaymentGatewayService(properties, new ChannelSelector(registry), List.of(provider),
                mock(DemoOrderService.class), mock(DemoMerchantService.class), mock(OnboardingRecordService.class),
                mock(ComplaintRecordService.class), relations, recordService);
    }

    @Test
    void reserveFailureStopsBeforeProviderAndNeverFallsBack() {
        when(records.reserve(any(), any())).thenThrow(new ProfitSharingRecordException("disk unavailable", new RuntimeException()));
        assertThatThrownBy(() -> gateway.profitSharing(request)).isInstanceOf(ProfitSharingRecordException.class);
        verify(provider, never()).profitSharing(any(), any());
        verify(records, times(1)).reserve(eq(first), eq(request));
    }

    @Test
    void responseSaveFailureDoesNotSubmitAgainOrReportSuccess() {
        doThrow(new ProfitSharingRecordException("save failed after response", new RuntimeException()))
                .when(records).recordResponse(any(), any(), any());
        assertThatThrownBy(() -> gateway.profitSharing(request)).isInstanceOf(ProfitSharingRecordException.class);
        var ordered = inOrder(records, provider);
        ordered.verify(records).reserve(first, request);
        ordered.verify(provider).profitSharing(first, request);
        ordered.verify(records).recordResponse(first, request, success);
        verify(provider, times(1)).profitSharing(any(), any());
    }

    @Test
    void upstreamUnknownResultKeepsReservedRecordAndNeverTriesAnotherChannel() {
        when(provider.profitSharing(any(), any())).thenThrow(new GatewayException("TIMEOUT", "Upstream result unknown"));
        assertThat(gateway.profitSharing(request).status()).isEqualTo(PaymentStatus.FAILED);
        verify(records).reserve(first, request);
        verify(records, never()).recordResponse(any(), any(), any());
        verify(provider, times(1)).profitSharing(any(), any());
    }

    @Test
    void savedSuccessfulRequestReturnsWithoutAnotherSplit() {
        when(records.reserve(any(), any())).thenReturn(success);
        assertThat(gateway.profitSharing(request).status()).isEqualTo(PaymentStatus.SUCCESS);
        verify(provider, never()).profitSharing(any(), any());
    }

    @Test
    void retryAfterResultPersistenceFailureDoesNotMakeASecondFundingCall() {
        ProfitSharingRecordService actualRecords = spy(new ProfitSharingRecordService(mock(DemoOrderService.class)));
        gateway = createGateway(actualRecords);
        doThrow(new ProfitSharingRecordException("save failed after response", new RuntimeException()))
                .when(actualRecords).recordResponse(any(), any(), any());
        assertThatThrownBy(() -> gateway.profitSharing(request)).isInstanceOf(ProfitSharingRecordException.class);
        assertThatThrownBy(() -> gateway.profitSharing(request)).isInstanceOf(ProfitSharingRecordException.class)
                .hasMessageContaining("不会再次提交");
        verify(provider, times(1)).profitSharing(any(), any());
        assertThat(actualRecords.search(null, null, "SPLIT", null, null)).hasSize(1)
                .allMatch(record -> "PENDING".equals(record.status()));
    }

    private static PaymentGatewayProperties.Channel channel(String id, int priority) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId(id); channel.setProvider("ALIPAY"); channel.setEnabled(true); channel.setDailyEnabled(true);
        channel.setPriority(priority);
        return channel;
    }
}
