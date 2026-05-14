package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;

import java.math.BigDecimal;
import java.util.Set;

public record DemoMerchantCreateRequest(
        String merchantId,
        String name,
        BigDecimal feeRate,
        String status,
        BigDecimal todayAmount,
        Set<String> channelIds,
        RoutingMode routingMode
) {
}
