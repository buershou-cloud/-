package com.example.payments.payout;

import java.util.List;

public record MerchantPayoutBatchView(
        int total,
        int processed,
        int rejected,
        List<MerchantPayoutBatchItemResult> results
) {
}
