package com.example.payments.gateway.douyin;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinPayClientTest {

    @Test
    void includesGatewayFieldDetailInErrorMessage() {
        String message = DouyinPayClient.gatewayErrorMessage(Map.of(
                "code", "PARAM_ERROR",
                "message", "参数错误",
                "detail", Map.of(
                        "field", "out_trade_no",
                        "issue", "OutTradeNo is invalid.",
                        "location", "body"
                )
        ), "");

        assertThat(message).isEqualTo(
                "参数错误（字段：out_trade_no；原因：OutTradeNo is invalid.；位置：body）"
        );
    }
    @Test
    void extractsDiagnosticLogIdFromResponseHeader() {
        String logId = DouyinPayClient.extractResponseLogId(
                Map.of("X-Tt-Logid", "20260723ABC123"),
                Map.of("log_id", "body-log-id")
        );

        assertThat(logId).isEqualTo("20260723ABC123");
    }

    @Test
    void fallsBackToDiagnosticLogIdInResponseBody() {
        String logId = DouyinPayClient.extractResponseLogId(
                Map.of(),
                Map.of("logId", "20260723BODY456")
        );

        assertThat(logId).isEqualTo("20260723BODY456");
    }
}
