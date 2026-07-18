package com.example.payments.payout;

public record MerchantPayoutBatchItemResult(
        int index,
        String requestedOutBizNo,
        MerchantPayoutView payout,
        String error
) {
}
