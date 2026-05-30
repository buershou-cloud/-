package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/channels")
public class CashierQrController {

    private static final String MINI_CASHIER_PAGE = "pages/cashier/index";

    private final ChannelRegistry channelRegistry;

    public CashierQrController(ChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @GetMapping(value = "/{channelId}/cashier-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] cashierQr(
            @PathVariable String channelId,
            @RequestParam(required = false) PaymentProduct product,
            HttpServletRequest request
    ) throws IOException, WriterException {
        PaymentGatewayProperties.Channel channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
        if (product != null && !channel.getProducts().contains(product)) {
            throw new IllegalArgumentException("Product is not enabled on channel: " + product);
        }

        String cashierUrl = isJsapi(product)
                ? miniProgramCashierUrl(channel, channelId, product, request)
                : UriComponentsBuilder.fromUriString(RequestUrlSupport.origin(request))
                        .path("/cashier.html")
                        .queryParam("channelId", channelId)
                        .queryParamIfPresent("product", java.util.Optional.ofNullable(product).map(Enum::name))
                        .build()
                        .toUriString();

        BitMatrix matrix = new QRCodeWriter().encode(cashierUrl, BarcodeFormat.QR_CODE, 320, 320);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
    }

    private static boolean isJsapi(PaymentProduct product) {
        return product == PaymentProduct.ALIPAY_JSAPI || product == PaymentProduct.ALIPAY_DIRECT_JSAPI;
    }

    private static String miniProgramCashierUrl(
            PaymentGatewayProperties.Channel channel,
            String channelId,
            PaymentProduct product,
            HttpServletRequest request
    ) {
        String appId = channel.getAlipay().getAppId();
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("Alipay JSAPI cashier QR requires channel appId");
        }
        String query = UriComponentsBuilder.newInstance()
                .queryParam("baseUrl", RequestUrlSupport.origin(request))
                .queryParam("channelId", channelId)
                .queryParam("product", product.name())
                .build()
                .getQuery();
        return "alipays://platformapi/startapp"
                + "?appId=" + encode(appId)
                + "&page=" + encode(MINI_CASHIER_PAGE)
                + "&query=" + encode(query == null ? "" : query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
