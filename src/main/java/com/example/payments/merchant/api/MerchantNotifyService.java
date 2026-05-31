package com.example.payments.merchant.api;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MerchantNotifyService {

    private final JdbcTemplate jdbcTemplate;
    private final DemoMerchantService merchantService;
    private final MerchantSignatureService signatureService;
    private final HttpClient httpClient;

    @Autowired
    public MerchantNotifyService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            DemoMerchantService merchantService,
            MerchantSignatureService signatureService
    ) {
        this(
                jdbcTemplateProvider.getIfAvailable(),
                merchantService,
                signatureService
        );
    }

    private MerchantNotifyService(
            JdbcTemplate jdbcTemplate,
            DemoMerchantService merchantService,
            MerchantSignatureService signatureService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.merchantService = merchantService;
        this.signatureService = signatureService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public void notifyPayment(DemoOrderView order, String alipayTradeStatus) {
        if (jdbcTemplate == null || order == null || merchantService == null || signatureService == null) {
            return;
        }
        notifyTarget(order.outTradeNo()).ifPresent(target -> send(order, alipayTradeStatus, target));
    }

    private Optional<NotifyTarget> notifyTarget(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT merchant_id, notify_url
                        FROM pay_order
                        WHERE out_trade_no = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String notifyUrl = rs.getString("notify_url");
                    if (notifyUrl == null || notifyUrl.isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(new NotifyTarget(rs.getString("merchant_id"), notifyUrl.trim()));
                },
                outTradeNo
        );
    }

    private void send(DemoOrderView order, String alipayTradeStatus, NotifyTarget target) {
        try {
            DemoMerchantView merchant = merchantService.detail(target.merchantId());
            String signType = signatureService.defaultSignType(merchant);
            Map<String, Object> payload = payload(order, alipayTradeStatus, signType);
            payload.put("sign", signatureService.signForMerchant(merchant, signType, payload));
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.notifyUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form(payload), StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException ex) {
            // Merchant callback failures must not make Alipay retry a verified notify forever.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            // Merchant callback failures must not make Alipay retry a verified notify forever.
        }
    }

    private Map<String, Object> payload(DemoOrderView order, String alipayTradeStatus, String signType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", order.merchantId());
        payload.put("outTradeNo", order.outTradeNo());
        payload.put("tradeNo", order.tradeNo());
        payload.put("channelId", order.channelId());
        payload.put("productName", order.productName());
        payload.put("totalAmount", order.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("tradeStatus", alipayTradeStatus);
        payload.put("status", order.status().name());
        payload.put("notifyTime", Instant.now().toString());
        payload.put("signType", signType);
        return payload;
    }

    private static String form(Map<String, Object> payload) {
        return payload.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().toString()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record NotifyTarget(String merchantId, String notifyUrl) {
    }
}
