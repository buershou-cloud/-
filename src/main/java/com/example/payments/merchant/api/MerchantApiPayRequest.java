package com.example.payments.merchant.api;

import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import com.example.payments.merchant.DemoMerchantView;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MerchantApiPayRequest(
        @NotBlank String merchantId,
        @NotNull PaymentProduct product,
        @NotBlank String outTradeNo,
        @NotBlank String subject,
        @NotNull @DecimalMin("0.01") BigDecimal totalAmount,
        String authCode,
        String buyerId,
        String buyerOpenId,
        String quitUrl,
        String timeoutExpress,
        String notifyUrl,
        String returnUrl,
        String appAuthToken,
        RoutingMode routingMode,
        List<String> channelIds,
        Map<String, Object> extra,
        Map<String, Object> settleInfo,
        Map<String, Object> royaltyInfo,
        @NotBlank String signType,
        @NotBlank String timestamp,
        @NotBlank String nonce,
        @NotBlank String sign
) implements MerchantSignedRequest {

    public PayCreateRequest toPayCreateRequest(DemoMerchantView merchant, List<String> safeChannelIds) {
        Map<String, Object> safeExtra = new LinkedHashMap<>();
        if (extra != null) {
            safeExtra.putAll(extra);
        }
        safeExtra.put("merchantId", merchant.merchantId());
        safeExtra.put("merchantName", merchant.name());
        return new PayCreateRequest(
                product,
                outTradeNo,
                subject,
                totalAmount,
                authCode,
                buyerId,
                buyerOpenId,
                quitUrl,
                timeoutExpress,
                notifyUrl,
                returnUrl,
                appAuthToken,
                routingMode,
                safeChannelIds,
                safeExtra,
                settleInfo,
                royaltyInfo
        );
    }
}
