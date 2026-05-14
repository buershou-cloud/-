package com.example.payments.merchant;

import java.util.Set;

public record MerchantCashierInfo(
        String merchantId,
        String name,
        Set<String> channelIds
) {
    public static MerchantCashierInfo from(DemoMerchantView merchant) {
        return new MerchantCashierInfo(
                merchant.merchantId(),
                merchant.name(),
                merchant.channelIds()
        );
    }
}
