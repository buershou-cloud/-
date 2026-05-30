package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CashierQrControllerTest {

    @Test
    void jsapiCashierQrUsesH5CashierUrl() throws Exception {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("ali-jsapi");
        channel.setProducts(new LinkedHashSet<>(Set.of(PaymentProduct.ALIPAY_JSAPI)));
        channel.getAlipay().setAppId("2021000000000000");
        properties.setChannels(List.of(channel));
        CashierQrController controller = new CashierQrController(new ChannelRegistry(properties));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/channels/ali-jsapi/cashier-qr");
        request.setScheme("https");
        request.setServerName("pay.example.test");
        request.setServerPort(443);

        byte[] qr = controller.cashierQr("ali-jsapi", PaymentProduct.ALIPAY_JSAPI, request);
        String decoded = PaymentCodeDecodeController.decodeImage(ImageIO.read(new ByteArrayInputStream(qr)));

        assertThat(decoded)
                .isEqualTo("https://pay.example.test/cashier.html?channelId=ali-jsapi&product=ALIPAY_JSAPI")
                .doesNotContain("platformapi/startapp")
                .doesNotContain("pages/cashier/index");
    }
}
