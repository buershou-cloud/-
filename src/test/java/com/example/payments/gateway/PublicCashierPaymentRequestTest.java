package com.example.payments.gateway;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCashierPaymentRequestTest {
    @Test
    void arbitraryUpstreamFieldsAndRecipientOverridesNeverSurviveThePublicBoundary() {
        PayCreateRequest safe = PublicCashierPaymentRequest.forChannel(request(PaymentProduct.ALIPAY_PAGE,
                "https://attacker.example/return", Map.of("cashier", true, "sub_merchant", Map.of("merchant_id", "attacker"),
                        "seller_id", "attacker", "notify_url", "https://attacker.example/notify", "extend_params", Map.of("x", "y"))), channel());
        assertThat(safe.appAuthToken()).isNull();
        assertThat(safe.settleInfo()).isNull();
        assertThat(safe.royaltyInfo()).isNull();
        assertThat(safe.extra()).containsOnlyKeys("cashier");
        assertThat(safe.notifyUrl()).isEqualTo("https://pay.example/api/v1/alipay/notify/main");
        assertThat(safe.returnUrl()).isEqualTo("https://pay.example/cashier.html");
        assertThat(safe.quitUrl()).isEqualTo(safe.returnUrl());
    }

    @Test
    void ordinaryCashierFieldsRemainForEveryProductAndMobilePagePayKeepsItsReturn() {
        for (PaymentProduct product : PaymentProduct.values()) {
            PayCreateRequest safe = PublicCashierPaymentRequest.forChannel(request(product,
                    "https://pay.example/cashier.html?channelId=main", Map.of("merchantId", "M1", "merchantName", "Merchant",
                            "integration_type", "ALIAPP", "request_from_url", "https://attacker.example")), channel());
            assertThat(safe.product()).isEqualTo(product);
            assertThat(safe.totalAmount()).isEqualByComparingTo("1.00");
            assertThat(safe.authCode()).isEqualTo("AUTH");
            assertThat(safe.buyerId()).isEqualTo("BUYER");
            assertThat(safe.buyerOpenId()).isEqualTo("OPENID");
            assertThat(safe.timeoutExpress()).isEqualTo("10m");
            assertThat(safe.returnUrl()).isEqualTo("https://pay.example/cashier.html?channelId=main");
            assertThat(safe.extra()).containsEntry("merchantId", "M1").containsEntry("integration_type", "ALIAPP")
                    .containsEntry("request_from_url", safe.returnUrl());
        }
    }

    @Test
    void douyinProfitSharingCheckboxDoesNotPermitSettlementRecipientInjection() {
        PayCreateRequest safe = PublicCashierPaymentRequest.sanitize(request(PaymentProduct.DOUYIN_H5, null,
                Map.of("settle_info", Map.of("profit_sharing", true, "settle_detail_infos", List.of(Map.of("trans_in", "attacker"))))));
        assertThat(safe.extra()).containsEntry("settle_info", Map.of("profit_sharing", true));
        assertThat(safe.settleInfo()).isNull();
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("main");
        channel.setProvider("ALIPAY");
        channel.getAlipay().setNotifyUrl("https://pay.example/api/v1/alipay/notify/main");
        channel.getAlipay().setReturnUrl("https://pay.example/cashier.html");
        return channel;
    }

    private static PayCreateRequest request(PaymentProduct product, String returnUrl, Map<String, Object> extra) {
        return new PayCreateRequest(product, "ORDER", "Subject", new BigDecimal("1.00"), "AUTH", "BUYER", "OPENID",
                returnUrl, "10m", "https://attacker.example/notify", returnUrl, "attacker-token", null, List.of("main"),
                extra, Map.of("settle_detail_infos", List.of(Map.of("trans_in", "attacker"))), Map.of("royalty_type", "transfer"));
    }
}
