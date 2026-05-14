package com.example.payments.domain;

import java.util.List;

public record ProfitSharingBatchResult(
        String channelId,
        int total,
        int success,
        int failed,
        String message,
        List<ProfitSharingBatchItem> items
) {
}
