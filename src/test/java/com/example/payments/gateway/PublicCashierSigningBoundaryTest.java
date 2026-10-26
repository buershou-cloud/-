package com.example.payments.gateway;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.gateway.alipay.AlipayPaymentProvider;
import com.example.payments.gateway.alipay.AlipayRequestOptions;
import com.example.payments.order.DemoOrderService;
import com.example.payments.web.PaymentController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCashierSigningBoundaryTest {
    @Test
    void anonymousControllerCannotSendRecipientTokenOrCallbackOverridesToAlipaySigning() {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("direct");
        channel.setProvider("ALIPAY_DIRECT");
        channel.setEnabled(true);
        channel.setDailyEnabled(true);
        channel.getAlipay().setSubMerchantId("server-submerchant");
        channel.getAlipay().setNotifyUrl("https://pay.example/api/v1/alipay/notify/direct");
        properties.getChannels().add(channel);
        DemoOrderService orders = new DemoOrderService();
        CapturingClient client = new CapturingClient();
        PaymentGatewayService gateway = new PaymentGatewayService(properties, new ChannelSelector(new ChannelRegistry(properties)),
                List.of(new AlipayPaymentProvider(properties, client)), orders, null, null, null, null, null);
        PaymentController controller = new PaymentController(gateway, null, null, null, orders);
        PayCreateRequest request = request("PUBLIC");

        assertThat(controller.pay(request, new MockHttpServletRequest()).status()).isEqualTo(PaymentStatus.CREATED);

        assertThat(client.biz).containsEntry("sub_merchant", Map.of("merchant_id", "server-submerchant"))
                .containsEntry("integration_type", "ALIAPP").containsEntry("request_from_url", request.returnUrl())
                .doesNotContainKeys("settle_info", "royalty_info", "seller_id", "notify_url");
        assertThat(client.options.appAuthToken()).isNull();
        assertThat(client.options.notifyUrl()).isEqualTo(channel.getAlipay().getNotifyUrl());
        assertThat(client.options.returnUrl()).isEqualTo(request.returnUrl());

        // The pre-existing authenticated/service API path retains its contract; only payPublic is narrowed.
        gateway.pay(request("AUTHENTICATED"));
        assertThat(client.biz).containsEntry("sub_merchant", Map.of("merchant_id", "client-submerchant"))
                .containsKey("settle_info").containsKey("royalty_info");
        assertThat(client.options.appAuthToken()).isEqualTo("client-token");
    }

    private static PayCreateRequest request(String orderNo) {
        return new PayCreateRequest(PaymentProduct.ALIPAY_DIRECT_PAGE, orderNo, "Subject", new BigDecimal("1.00"),
                null, null, null, "https://pay.example/cashier.html?channelId=direct", "10m", "https://attacker.example/notify",
                "https://pay.example/cashier.html?channelId=direct", "client-token", null, List.of("direct"),
                Map.of("sub_merchant", Map.of("merchant_id", "client-submerchant"), "seller_id", "attacker", "integration_type", "ALIAPP"),
                Map.of("settle_detail_infos", List.of(Map.of("trans_in", "attacker"))), Map.of("royalty_type", "transfer"));
    }

    private static class CapturingClient extends AlipayOpenApiClient {
        private Map<String, Object> biz;
        private AlipayRequestOptions options;
        private CapturingClient() { super(new ObjectMapper()); }
        @Override
        public String pageForm(PaymentGatewayProperties.Channel channel, String method, Map<String, Object> business,
                               AlipayRequestOptions requestOptions) {
            biz = new LinkedHashMap<>(business);
            options = requestOptions;
            return "offline test form";
        }
    }
}
