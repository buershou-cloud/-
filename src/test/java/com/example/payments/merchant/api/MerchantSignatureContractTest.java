package com.example.payments.merchant.api;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MerchantSignatureContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void matchesSharedIndependentCrossLanguageVectors() throws Exception {
        var service = new MerchantSignatureService(mock(DemoMerchantService.class), mapper);
        var vectors = mapper.readValue(getClass().getResourceAsStream("/merchant-signature-vectors.json"),
                new TypeReference<List<Map<String, Object>>>() {});
        for (var vector : vectors) {
            var merchant = mock(DemoMerchantView.class);
            when(merchant.md5Key()).thenReturn((String) vector.get("key"));
            @SuppressWarnings("unchecked") var payload = (Map<String, Object>) vector.get("payload");
            assertThat(service.signForMerchant(merchant, "MD5", payload))
                    .as((String) vector.get("name"))
                    .isEqualTo(md5(vector.get("canonical") + (String) vector.get("key")));
        }
    }

    @Test void portableResponseRemainsVerifiableAfterActualJsonSerialization() throws Exception {
        var merchants = mock(DemoMerchantService.class);
        var merchant = mock(DemoMerchantView.class);
        when(merchant.md5Key()).thenReturn("test-key");
        var service = new MerchantSignatureService(merchants, mapper);
        var gateway = new GatewayResponse("ali-main", PaymentStatus.SUCCESS, "10000", "OK", "ORDER1", "TRADE1",
                null, null, Map.of("decimal", new BigDecimal("1.00"), "double", 1.0, "large", 9007199254740993L), List.of());
        var response = service.successCanonical(merchant, "MD5", gateway);
        Map<String, Object> wire = mapper.readValue(mapper.writeValueAsBytes(response), new TypeReference<>() {});
        assertThat(service.signForMerchant(merchant, "MD5", wire)).isEqualTo(response.sign());
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) response.data();
        @SuppressWarnings("unchecked") var raw = (Map<String, Object>) data.get("raw");
        assertThat(raw).containsEntry("decimal", "1.00").containsEntry("double", "1.0").containsEntry("large", "9007199254740993");
        assertThat(data).doesNotContainKey("qrCode");
        assertThat(service.success(merchant, "MD5", gateway).data()).isSameAs(gateway);
    }

    @Test void authenticatesIndependentCheckAndRejectsNonceReplay() throws Exception {
        var merchants = mock(DemoMerchantService.class);
        var merchant = mock(DemoMerchantView.class);
        when(merchant.merchantId()).thenReturn("M1001");
        when(merchant.md5Key()).thenReturn("test-key");
        when(merchant.status()).thenReturn("正常");
        when(merchant.signMode()).thenReturn("MD5_RSA2");
        when(merchants.detail("M1001")).thenReturn(merchant);
        var service = new MerchantSignatureService(merchants, mapper);
        String timestamp = Instant.now().toString();
        var request = new CheckPayload("M1001", "MD5", timestamp, "nonce-unique",
                md5("merchantId=M1001&nonce=nonce-unique&signType=MD5&timestamp=" + timestamp + "test-key"));
        assertThat(service.verify(request)).isSameAs(merchant);
        assertThatThrownBy(() -> service.verify(request)).isInstanceOf(MerchantApiException.class)
                .extracting("code").isEqualTo("NONCE_REUSED");
        when(merchant.status()).thenReturn("停用");
        assertThatThrownBy(() -> service.verify(request)).isInstanceOf(MerchantApiException.class)
                .extracting("code").isEqualTo("MERCHANT_DISABLED");
    }

    @Test void rejectsExpiredTimestampWithActionableCode() {
        var merchants = mock(DemoMerchantService.class);
        var merchant = mock(DemoMerchantView.class);
        when(merchant.status()).thenReturn("正常");
        when(merchant.signMode()).thenReturn("MD5");
        when(merchants.detail("M1001")).thenReturn(merchant);
        var service = new MerchantSignatureService(merchants, mapper);
        assertThatThrownBy(() -> service.verify(new CheckPayload("M1001", "MD5", "2020-01-01T00:00:00Z", "nonce", "ignored")))
                .isInstanceOf(MerchantApiException.class).extracting("code").isEqualTo("TIMESTAMP_EXPIRED");
    }

    private static String md5(String text) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    record CheckPayload(String merchantId, String signType, String timestamp, String nonce, String sign) implements MerchantSignedRequest {}
}
