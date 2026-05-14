package com.example.payments.gateway.alipay;

import com.example.payments.config.PaymentGatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlipayOpenApiClientTest {

    @Test
    void pageFormPutsCharsetOnGatewayUrl() throws Exception {
        AlipayOpenApiClient client = new AlipayOpenApiClient(new ObjectMapper());
        PaymentGatewayProperties.Channel channel = channel();

        String form = client.pageForm(
                channel,
                "alipay.trade.wap.pay",
                Map.of("out_trade_no", "T1001", "total_amount", "1.00", "subject", "test"),
                new AlipayRequestOptions(null, null, null)
        );

        assertThat(form).contains("action=\"https://openapi.alipay.com/gateway.do?charset=UTF-8\"");
        assertThat(form).contains("name=\"charset\" value=\"UTF-8\"");
    }

    @Test
    void pageUrlContainsSignedQueryParams() throws Exception {
        AlipayOpenApiClient client = new AlipayOpenApiClient(new ObjectMapper());
        PaymentGatewayProperties.Channel channel = channel();

        String url = client.pageUrl(
                channel,
                "alipay.trade.wap.pay",
                Map.of("out_trade_no", "T1001", "total_amount", "1.00", "subject", "test"),
                new AlipayRequestOptions(null, null, null)
        );

        assertThat(url).startsWith("https://openapi.alipay.com/gateway.do?");
        assertThat(url)
                .contains("method=alipay.trade.wap.pay")
                .contains("charset=UTF-8")
                .contains("sign=");
    }

    private static PaymentGatewayProperties.Channel channel() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-test");
        channel.getAlipay().setAppId("2021000000000000");
        channel.getAlipay().setMerchantPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        return channel;
    }
}
