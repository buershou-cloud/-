package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.payout.MerchantPayoutBatchItemRequest;
import com.example.payments.payout.MerchantPayoutBatchRequest;
import com.example.payments.payout.MerchantPayoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MerchantPayoutControllerTest {

    private final MerchantPayoutService payoutService = mock(MerchantPayoutService.class);
    private final AdminAuthService authService = mock(AdminAuthService.class);
    private final MerchantPayoutController controller = new MerchantPayoutController(payoutService, authService);

    @AfterEach
    void shutdownExecutor() {
        controller.shutdownBatchExecutor();
    }

    @Test
    void rejectsDuplicateExplicitNumbersBeforeSubmittingBatch() {
        when(authService.paymentPasswordConfigured()).thenReturn(true);
        when(authService.verifyPaymentPassword("paypass123")).thenReturn(true);
        MerchantPayoutBatchRequest request = new MerchantPayoutBatchRequest(
                List.of(item("PAYOUT-001"), item("payout-001")),
                "paypass123"
        );

        assertThatThrownBy(() -> controller.createBatch(request, new MockHttpServletRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复的代付单号");
    }

    private static MerchantPayoutBatchItemRequest item(String outBizNo) {
        return new MerchantPayoutBatchItemRequest(
                "alipay-channel",
                outBizNo,
                new BigDecimal("1.00"),
                "ALIPAY_LOGON_ID",
                "buyer@example.com",
                "测试收款人",
                "商家代付",
                "测试代付",
                null,
                null,
                null
        );
    }
}
