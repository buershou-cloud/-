package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.order.DemoOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alipay")
public class AlipayNotifyController {

    private final ChannelRegistry channelRegistry;
    private final AlipayOpenApiClient alipayOpenApiClient;
    private final DemoOrderService orderService;

    public AlipayNotifyController(
            ChannelRegistry channelRegistry,
            AlipayOpenApiClient alipayOpenApiClient,
            DemoOrderService orderService
    ) {
        this.channelRegistry = channelRegistry;
        this.alipayOpenApiClient = alipayOpenApiClient;
        this.orderService = orderService;
    }

    @PostMapping(path = "/notify/{channelId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> notify(
            @PathVariable String channelId,
            @RequestParam MultiValueMap<String, String> form
    ) {
        PaymentGatewayProperties.Channel channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
        Map<String, String> params = firstValues(form);
        if (!alipayOpenApiClient.verifyNotify(channel, params)) {
            return ResponseEntity.badRequest().body("failure");
        }

        String outTradeNo = params.get("out_trade_no");
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return ResponseEntity.badRequest().body("failure");
        }
        orderService.recordAlipayNotify(
                outTradeNo,
                params.get("trade_no"),
                channelId,
                amount(params),
                params.get("trade_status")
        );
        return ResponseEntity.ok("success");
    }

    @GetMapping(path = "/return/{channelId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> returnUrl(
            @PathVariable String channelId,
            @RequestParam MultiValueMap<String, String> params
    ) {
        channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
        String outTradeNo = value(params, "out_trade_no");
        String tradeNo = value(params, "trade_no");
        String totalAmount = value(params, "total_amount");
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>支付返回</title>
                  <style>
                    body { margin:0; font-family:"Microsoft YaHei", system-ui, sans-serif; background:#f4f7fb; color:#0f172a; }
                    main { min-height:100vh; display:grid; place-items:center; padding:24px; }
                    section { width:min(560px, 100%%); border:1px solid #e2e8f0; border-radius:8px; background:#fff; padding:24px; box-shadow:0 18px 50px rgba(15,23,42,.08); }
                    h1 { margin:0 0 14px; font-size:24px; }
                    p { margin:8px 0; color:#475569; font-weight:700; }
                  </style>
                </head>
                <body>
                <main><section>
                  <h1>支付返回</h1>
                  <p>支付同步返回已接收，请以异步通知和订单查询结果为准。</p>
                  <p>通道：%s</p>
                  <p>订单号：%s</p>
                  <p>支付宝交易号：%s</p>
                  <p>金额：%s</p>
                </section></main>
                </body>
                </html>
                """.formatted(escape(channelId), escape(outTradeNo), escape(tradeNo), escape(totalAmount));
        return ResponseEntity.ok(html);
    }

    private static Map<String, String> firstValues(MultiValueMap<String, String> form) {
        Map<String, String> result = new LinkedHashMap<>();
        form.forEach((key, values) -> result.put(key, values == null || values.isEmpty() ? null : values.get(0)));
        return result;
    }

    private static String value(MultiValueMap<String, String> params, String key) {
        String value = params.getFirst(key);
        return value == null || value.isBlank() ? "-" : value;
    }

    private static BigDecimal amount(Map<String, String> params) {
        String value = params.get("total_amount");
        if (value == null || value.isBlank()) {
            value = params.get("receipt_amount");
        }
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private static String value(Map<String, String> params, String key) {
        String value = params.get(key);
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escape(String value) {
        return value == null
                ? "-"
                : value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
