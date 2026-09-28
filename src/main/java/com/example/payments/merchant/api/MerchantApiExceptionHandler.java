package com.example.payments.merchant.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MerchantApiController.class)
public class MerchantApiExceptionHandler {
    @ExceptionHandler(MerchantApiException.class)
    public ResponseEntity<Map<String, String>> handle(MerchantApiException error) {
        HttpStatus status = switch (error.code()) {
            case "ORDER_CONFLICT", "REFUND_CONFLICT" -> HttpStatus.CONFLICT;
            case "ORDER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "CHANNEL_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
