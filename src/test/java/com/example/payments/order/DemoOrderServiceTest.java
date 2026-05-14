package com.example.payments.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoOrderServiceTest {

    @Test
    void shareableByChannelSkipsUnavailableAndAlreadySharedOrders() {
        DemoOrderService service = new DemoOrderService();

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .contains("P20260512143001")
                .doesNotContain("P20260512141205");

        service.markProfitShared("P20260512143001");

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .doesNotContain("P20260512143001");
        assertThat(service.shareableByChannel("ali-main", true))
                .extracting(DemoOrderView::outTradeNo)
                .contains("P20260512143001");
    }
}
