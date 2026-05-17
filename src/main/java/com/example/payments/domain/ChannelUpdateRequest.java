package com.example.payments.domain;

import java.math.BigDecimal;
import java.util.Set;

public record ChannelUpdateRequest(
        Boolean enabled,
        Boolean dailyEnabled,
        Integer priority,
        Integer weight,
        BigDecimal payMin,
        BigDecimal payMax,
        Set<PaymentProduct> products,
        String gatewayUrl,
        String appId,
        String merchantPrivateKey,
        String alipayPublicKey,
        String credentialMode,
        String appCertSn,
        String alipayCertSn,
        String alipayRootCertSn,
        String appCertContent,
        String alipayCertContent,
        String alipayRootCertContent,
        String appAuthToken,
        String subMerchantId,
        String notifyUrl,
        String returnUrl
) {
}
