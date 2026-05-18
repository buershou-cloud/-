package com.example.payments.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DemoOrderServiceTest {

    @Test
    void shareableByChannelSkipsUnavailableAndAlreadySharedOrders() {
        DemoOrderService service = new DemoOrderService();
        service.recordPaymentCreated(
                "ORDER-SHARE-001",
                "TRADE-SHARE-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("10.00"),
                false
        );
        service.complete("ORDER-SHARE-001");
        service.recordPaymentCreated(
                "ORDER-REFUND-001",
                "TRADE-REFUND-001",
                "ali-main",
                "M10001",
                "测试商户",
                "当面付",
                new BigDecimal("20.00"),
                false
        );
        service.complete("ORDER-REFUND-001");
        service.refund("ORDER-REFUND-001");

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .contains("ORDER-SHARE-001")
                .doesNotContain("ORDER-REFUND-001");

        service.markProfitShared("ORDER-SHARE-001");

        assertThat(service.shareableByChannel("ali-main", false))
                .extracting(DemoOrderView::outTradeNo)
                .doesNotContain("ORDER-SHARE-001");
        assertThat(service.shareableByChannel("ali-main", true))
                .extracting(DemoOrderView::outTradeNo)
                .contains("ORDER-SHARE-001");
    }
}
