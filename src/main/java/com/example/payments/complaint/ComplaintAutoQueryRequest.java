package com.example.payments.complaint;

import java.util.Map;

public record ComplaintAutoQueryRequest(
        Boolean enabled,
        Integer lookbackMinutes,
        Integer pageSize,
        String method,
        String appAuthToken,
        Map<String, Object> extra
) {
}
