package com.example.payments.web;

import com.example.payments.complaint.ComplaintAutoQueryService;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.order.DemoOrderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfitSharingControllerTest {
    private final PaymentGatewayService gateway = mock(PaymentGatewayService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(
            gateway, mock(ComplaintAutoQueryService.class), mock(OnboardingRecordService.class),
            mock(ComplaintRecordService.class), mock(DemoOrderService.class))).build();

    @Test
    void remainingAmountCanBeQueriedBeforeFirstSplit() throws Exception {
        mvc.perform(post("/api/v1/payments/profit-sharing/remaining")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tradeNo":"DY1001","channelIds":["douyin-main"]}
                        """))
                .andExpect(status().isOk());

        ArgumentCaptor<ProfitSharingQueryRequest> request = ArgumentCaptor.forClass(ProfitSharingQueryRequest.class);
        verify(gateway).profitSharingRemainingAmount(request.capture());
        assertThat(request.getValue().outRequestNo()).isNull();
        assertThat(request.getValue().tradeNo()).isEqualTo("DY1001");
        assertThat(request.getValue().channelIds()).containsExactly("douyin-main");
    }

    @Test
    void splitQueryStillRequiresItsOriginalRequestNumber() throws Exception {
        mvc.perform(post("/api/v1/payments/profit-sharing/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"tradeNo":"DY1001","channelIds":["douyin-main"]}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
    }

    @Test
    void remainingAmountRequiresPaymentTransaction() throws Exception {
        mvc.perform(post("/api/v1/payments/profit-sharing/remaining")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"channelIds":["douyin-main"]}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
    }
}
