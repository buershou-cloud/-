package com.example.payments.domain;

public record ChannelAttempt(
        String channelId,
        boolean success,
        String code,
        String message
) {
}
