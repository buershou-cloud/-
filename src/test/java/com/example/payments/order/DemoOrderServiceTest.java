package com.example.payments.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DemoOrderServiceTest {

    @Test
    void shareableByChannelSkipsUnavailableAndAlreadySharedOrders() {
        DemoOrderService service = new DemoOrderService();

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .containsExactly("P20260512143001");

        service.markProfitShared("P20260512143001");

        assertThat(service.shareableByChannel("ali-main", false)).isEmpty();
        assertThat(service.shareableByChannel("ali-main", true))
                .extracting(DemoOrderView::outTradeNo)
                .containsExactly("P20260512143001");
    }
}
