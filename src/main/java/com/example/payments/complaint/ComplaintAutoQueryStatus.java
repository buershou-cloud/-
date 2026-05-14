package com.example.payments.complaint;

import com.example.payments.domain.GatewayResponse;

public record ComplaintAutoQueryStatus(
        boolean enabled,
        boolean running,
        int intervalSeconds,
        int lookbackMinutes,
        int pageSize,
        String lastStartedAt,
        String lastFinishedAt,
        String lastMessage,
        int runCount,
        GatewayResponse lastResult
) {
}
