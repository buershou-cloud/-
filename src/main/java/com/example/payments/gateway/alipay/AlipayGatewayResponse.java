package com.example.payments.gateway.alipay;

import java.util.Map;

public record AlipayGatewayResponse(
        String method,
        String responseKey,
        boolean success,
        String code,
        String message,
        String subCode,
        String subMessage,
        Map<String, Object> response,
        Map<String, Object> raw
) {
}
