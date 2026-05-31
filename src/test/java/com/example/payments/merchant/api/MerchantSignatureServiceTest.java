package com.example.payments.merchant.api;

import com.example.payments.domain.PaymentProduct;
import com.example.payments.merchant.DemoMerchantCreateRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantSignatureServiceTest {

    @Test
    void verifiesMd5SignedMerchantRequest() {
        DemoMerchantService merchantService = new DemoMerchantService();
        DemoMerchantView merchant = merchantService.create(new DemoMerchantCreateRequest(
                "MAPI1001",
                "API Merchant",
                new BigDecimal("0.60"),
                null,
                BigDecimal.ZERO,
                Set.of("ali-main"),
                null
        ));
        MerchantSignatureService signatureService = new MerchantSignatureService(merchantService, new ObjectMapper());
        String timestamp = Instant.now().toString();
        Map<String, Object> payload = payPayload(merchant.merchantId(), timestamp, "NONCE-1001", new BigDecimal("1.00"));
        String sign = signatureService.signForMerchant(merchant, "MD5", payload);

        MerchantApiPayRequest request = new MerchantApiPayRequest(
                merchant.merchantId(),
                PaymentProduct.ALIPAY_ORDER_CODE,
                "ORDER1001",
                "Test order",
                new BigDecimal("1.00"),
                null,
                null,
                null,
                null,
                null,
                "https://merchant.example/notify",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "MD5",
                timestamp,
                "NONCE-1001",
                sign
        );

        assertThat(signatureService.verify(request).merchantId()).isEqualTo(merchant.merchantId());
    }

    @Test
    void rejectsTamperedMerchantRequest() {
        DemoMerchantService merchantService = new DemoMerchantService();
        DemoMerchantView merchant = merchantService.create(new DemoMerchantCreateRequest(
                "MAPI1002",
                "API Merchant",
                new BigDecimal("0.60"),
                null,
                BigDecimal.ZERO,
                Set.of("ali-main"),
                null
        ));
        MerchantSignatureService signatureService = new MerchantSignatureService(merchantService, new ObjectMapper());
        String timestamp = Instant.now().toString();
        Map<String, Object> payload = payPayload(merchant.merchantId(), timestamp, "NONCE-1002", new BigDecimal("1.00"));
        String sign = signatureService.signForMerchant(merchant, "MD5", payload);

        MerchantApiPayRequest request = new MerchantApiPayRequest(
                merchant.merchantId(),
                PaymentProduct.ALIPAY_ORDER_CODE,
                "ORDER1001",
                "Test order",
                new BigDecimal("2.00"),
                null,
                null,
                null,
                null,
                null,
                "https://merchant.example/notify",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "MD5",
                timestamp,
                "NONCE-1002",
                sign
        );

        assertThatThrownBy(() -> signatureService.verify(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification failed");
    }

    private static Map<String, Object> payPayload(
            String merchantId,
            String timestamp,
            String nonce,
            BigDecimal amount
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchantId);
        payload.put("product", "ALIPAY_ORDER_CODE");
        payload.put("outTradeNo", "ORDER1001");
        payload.put("subject", "Test order");
        payload.put("totalAmount", amount);
        payload.put("notifyUrl", "https://merchant.example/notify");
        payload.put("signType", "MD5");
        payload.put("timestamp", timestamp);
        payload.put("nonce", nonce);
        return payload;
    }
}
