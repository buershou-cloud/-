package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DouyinRefundEvidenceTest {
    @Test
    void officialRefundResponseWithoutMerchantFieldsIsSupportedForChannelScopedQuery() {
        // Official SDK Refund: https://pkg.go.dev/github.com/douyinpay/douyinpay-go/services/refund#Refund
        DouyinRefundEvidence evidence = DouyinRefundEvidence.fromQueryResponse(channel(), payload());
        assertThat(evidence.refundAmount()).isEqualByComparingTo("4.00");
        assertThat(evidence.totalAmount()).isEqualByComparingTo("10.00");
        assertThat(evidence.refundId()).isEqualTo("DY-R1");
    }

    @Test
    void callbackMerchantMayComeFromTheSignedEncryptedResourceEnvelope() {
        // Official callback EncryptedResource contains mchid; the decrypted Refund can omit it.
        assertThat(DouyinRefundEvidence.fromNotification(channel(), payload(), "MCH1").outRefundNo()).isEqualTo("R1");
        assertThatThrownBy(() -> DouyinRefundEvidence.fromNotification(channel(), payload(), "OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DouyinRefundEvidence.fromNotification(channel(), payload(), null))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> payload = payload();
        payload.put("mchid", "OTHER");
        assertThatThrownBy(() -> DouyinRefundEvidence.fromNotification(channel(), payload, "MCH1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DouyinRefundEvidence.fromQueryResponse(channel(), payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOrFractionalRefundAmountsCannotBecomeEvidenceOfSuccess() {
        for (Map<String, Object> amount : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("refund", "1.5"), Map.of("refund", -1))) {
            Map<String, Object> payload = payload();
            payload.put("amount", amount);
            assertThatThrownBy(() -> DouyinRefundEvidence.fromQueryResponse(channel(), payload))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setProvider("DOUYIN");
        channel.getDouyin().setMchId("MCH1");
        channel.getDouyin().setAppId("APP1");
        return channel;
    }

    private static Map<String, Object> payload() {
        return new LinkedHashMap<>(Map.of("refund_id", "DY-R1", "out_refund_no", "R1", "transaction_id", "T1",
                "out_trade_no", "ONE", "status", "SUCCESS", "amount", Map.of("refund", 400, "total", 1000, "currency", "CNY")));
    }
}
