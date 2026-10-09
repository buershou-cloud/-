package com.example.payments.notification;

interface TelegramTransport {
    Result send(String botToken, String chatId, String text);

    record Result(boolean success, Long messageId, String code, String message, long retryAfterSeconds) {
        static Result failure(String code, String message, long retryAfterSeconds) {
            return new Result(false, null, code, message, retryAfterSeconds);
        }
    }
}
