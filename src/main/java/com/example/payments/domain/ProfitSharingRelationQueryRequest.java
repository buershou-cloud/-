package com.example.payments.domain;

import java.util.List;
import java.util.Map;

public record ProfitSharingRelationQueryRequest(
        String receiverAccount,
        String receiverType,
        String outRequestNo,
        Integer pageNum,
        Integer pageSize,
        String appAuthToken,
        List<String> channelIds,
        Map<String, Object> extra
) {
}
