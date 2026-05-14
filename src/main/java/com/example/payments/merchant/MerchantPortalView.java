package com.example.payments.merchant;

import com.example.payments.order.DemoOrderView;

import java.util.List;

public record MerchantPortalView(
        DemoMerchantView merchant,
        List<DemoOrderView> orders,
        String apiBase
) {
}
