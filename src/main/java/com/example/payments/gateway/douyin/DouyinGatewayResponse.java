package com.example.payments.gateway.douyin;

import java.util.Map;

public record DouyinGatewayResponse(
        int httpStatus,
        Map<String, Object> body,
        String rawBody,
        Map<String, String> headers
) {
}
