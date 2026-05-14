package com.example.payments.domain;

import java.util.List;
import java.util.Map;

public record ComplaintQueryRequest(
        String complaintId,
        String beginTime,
        String endTime,
        Integer pageNum,
        Integer pageSize,
        String method,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
