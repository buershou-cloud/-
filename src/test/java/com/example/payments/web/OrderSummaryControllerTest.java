package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.order.OrderSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderSummaryControllerTest {
    private final OrderSummaryService service = mock(OrderSummaryService.class);
    private final AdminAuthService auth = mock(AdminAuthService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(auth.hasUsername("admin")).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new OrderSummaryController(service))
                .addInterceptors(new AdminAuthInterceptor(auth))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void anonymousAndMerchantSessionsCannotReadGlobalFinancialSummary() throws Exception {
        mvc.perform(get("/api/v1/orders/summary").param("today", "2026-10-25").param("month", "2026-10"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/summary").sessionAttr("MERCHANT_ID", "M1")
                        .param("today", "2026-10-25").param("month", "2026-10"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void adminGetsUnfilteredSummaryAndUnknownReconciliationRemainsNull() throws Exception {
        when(service.summary("2026-10-25", "2026-10")).thenReturn(new OrderSummaryService.Summary(
                new BigDecimal("100"), 1, new BigDecimal("200"), 2, new BigDecimal("30"), 1,
                new BigDecimal("168.80"), new BigDecimal("1.20"), null,
                List.of(new OrderSummaryService.ReportRow("2026-10-25", 1, new BigDecimal("100"),
                        new BigDecimal("30"), "UNCONFIRMED"))));
        mvc.perform(get("/api/v1/orders/summary").sessionAttr(AdminSession.USERNAME_ATTRIBUTE, "admin")
                        .param("today", "2026-10-25").param("month", "2026-10")
                        .param("outTradeNo", "filtered-order"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.todayTradeAmount").value(100))
                .andExpect(jsonPath("$.todayRefundAmount").value(30))
                .andExpect(jsonPath("$.reportMismatch").isEmpty())
                .andExpect(jsonPath("$.reportRows[0].settlementStatus").value("UNCONFIRMED"));
        verify(service).summary("2026-10-25", "2026-10");
        verifyNoMoreInteractions(service);
    }
}
