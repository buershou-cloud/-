package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;

import java.util.Set;

public record MerchantRouting(
        Set<String> channelIds,
        RoutingMode routingMode
) {
}
