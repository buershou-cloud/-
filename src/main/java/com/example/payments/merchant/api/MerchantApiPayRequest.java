package com.example.payments.merchant.api;

import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import com.example.payments.merchant.DemoMerchantView;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

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

    private static final Set<String> RESERVED_EXTRA = Set.of(
            "merchantid", "merchantname", "merchantapirequest", "outtradeno", "tradeno", "outorderno",
            "outrequestno", "outrefundno", "totalamount", "refundamount", "amount", "subject", "body",
            "notifyurl", "returnurl", "appauthtoken", "appid", "mchid", "merchantuid",
            "buyerid", "buyeropenid", "authcode", "alipaymethod", "preauthmethod");

    public void validateMerchantFields() {
        if (appAuthToken != null && !appAuthToken.isBlank()) {
            throw new MerchantApiException("RESERVED_APP_AUTH_TOKEN", "Merchant API requests must use the authorization token configured on the bound channel");
        }
        if (totalAmount == null || totalAmount.signum() <= 0 || totalAmount.stripTrailingZeros().scale() > 2) {
            throw new MerchantApiException("INVALID_AMOUNT", "totalAmount must be positive with at most two decimal places");
        }
        if (outTradeNo == null || outTradeNo.isBlank() || !outTradeNo.equals(outTradeNo.trim())) {
            throw new MerchantApiException("INVALID_ORDER_ID", "outTradeNo must be nonblank without surrounding whitespace");
        }
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            try {
                URI uri = URI.create(notifyUrl);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException ex) {
                throw new MerchantApiException("INVALID_NOTIFY_URL", "notifyUrl must be an absolute HTTP(S) URL without credentials or fragment");
            }
        }
        if (extra != null) {
            for (String key : extra.keySet()) {
                if (key == null || RESERVED_EXTRA.contains(key.replace("_", "").toLowerCase(Locale.ROOT))) {
                    throw new MerchantApiException("RESERVED_EXTRA_FIELD", "extra contains a reserved field: " + key);
                }
            }
        }
    }

    public PayCreateRequest toPayCreateRequest(DemoMerchantView merchant, List<String> safeChannelIds) {
        Map<String, Object> safeExtra = new LinkedHashMap<>();
        if (extra != null) {
            safeExtra.putAll(extra);
        }
        safeExtra.put("merchantId", merchant.merchantId());
        safeExtra.put("merchantName", merchant.name());
        safeExtra.put("merchantApiRequest", true);
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
