package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.sharing.ProfitSharingRelationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentGatewayServiceTest {

    @Test
    void convertsAlipayPercentageToAnExplicitAmountBeforeCallingProvider() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.getRouting().setMaxAttempts(1);
        properties.getRouting().setFailover(false);

        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-main");
        channel.setProvider("ALIPAY");
        channel.setEnabled(true);
        channel.setDailyEnabled(true);

        ChannelRegistry registry = mock(ChannelRegistry.class);
        when(registry.all()).thenReturn(List.of(channel));
        when(registry.isEnabled(channel)).thenReturn(true);

        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.providerCode()).thenReturn("ALIPAY");
        when(provider.profitSharing(eq(channel), any())).thenReturn(successResponse());

        DemoOrderService orderService = mock(DemoOrderService.class);
        when(orderService.amountByIdentifier("ORDER-1001", "TRADE-1001"))
                .thenReturn(new BigDecimal("123.45"));

        ProfitSharingRelationService relationService = mock(ProfitSharingRelationService.class);
        when(relationService.isBound("ali-main", "loginName", "receiver@example.com")).thenReturn(true);

        PaymentGatewayService service = new PaymentGatewayService(
                properties,
                new ChannelSelector(registry),
                List.of(provider),
                orderService,
                mock(DemoMerchantService.class),
                mock(OnboardingRecordService.class),
                mock(ComplaintRecordService.class),
                relationService
        );

        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("royalty_type", "transfer");
        receiver.put("trans_in_type", "loginName");
        receiver.put("trans_in", "receiver@example.com");
        receiver.put("amount_percentage", new BigDecimal("20"));
        receiver.put("desc", "20% 分账");

        service.profitSharing(new ProfitSharingRequest(
                "ORDER-1001",
                "TRADE-1001",
                "PS_ORDER_1001",
                List.of(receiver),
                null,
                null,
                List.of("ali-main"),
                Map.of()
        ));

        ArgumentCaptor<ProfitSharingRequest> requestCaptor = ArgumentCaptor.forClass(ProfitSharingRequest.class);
        verify(provider).profitSharing(eq(channel), requestCaptor.capture());
        Map<String, Object> normalizedReceiver = requestCaptor.getValue().royaltyParameters().getFirst();
        assertThat(normalizedReceiver)
                .containsEntry("amount", "24.69")
                .doesNotContainKey("amount_percentage");
    }

    private static GatewayResponse successResponse() {
        return new GatewayResponse(
                "ali-main",
                PaymentStatus.SUCCESS,
                "10000",
                "Success",
                "ORDER-1001",
                "TRADE-1001",
                null,
                null,
                Map.of(),
                List.of()
        );
    }
}
