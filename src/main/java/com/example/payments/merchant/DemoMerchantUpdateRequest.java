package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;

import java.math.BigDecimal;
import java.util.Set;

public record DemoMerchantUpdateRequest(
        String name,
        BigDecimal feeRate,
        String status,
        BigDecimal todayAmount,
        Set<String> channelIds,
        RoutingMode routingMode
) {
}
