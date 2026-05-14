package com.example.payments.channel;

import com.example.payments.config.PaymentGatewayProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelRegistry {

    private final Map<String, PaymentGatewayProperties.Channel> channels;
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();

    public ChannelRegistry(PaymentGatewayProperties properties) {
        this.channels = properties.getChannels().stream()
                .collect(Collectors.toMap(
                        PaymentGatewayProperties.Channel::getId,
                        Function.identity(),
                        (left, right) -> right
                ));
    }

    public List<PaymentGatewayProperties.Channel> all() {
        return channels.values().stream()
                .sorted((left, right) -> {
                    int priority = Integer.compare(left.getPriority(), right.getPriority());
                    return priority != 0 ? priority : left.getId().compareTo(right.getId());
                })
                .toList();
    }

    public Optional<PaymentGatewayProperties.Channel> find(String channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    public PaymentGatewayProperties.Channel add(PaymentGatewayProperties.Channel channel) {
        if (channel.getId() == null || channel.getId().isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        PaymentGatewayProperties.Channel existing = channels.putIfAbsent(channel.getId(), channel);
        if (existing != null) {
            throw new IllegalArgumentException("Channel already exists: " + channel.getId());
        }
        return channel;
    }

    public PaymentGatewayProperties.Channel remove(String channelId) {
        PaymentGatewayProperties.Channel removed = channels.remove(channelId);
        if (removed == null) {
            throw new IllegalArgumentException("Unknown channel: " + channelId);
        }
        enabledOverrides.remove(channelId);
        return removed;
    }

    public boolean isEnabled(PaymentGatewayProperties.Channel channel) {
        return enabledOverrides.getOrDefault(channel.getId(), channel.isEnabled());
    }

    public PaymentGatewayProperties.Channel setEnabled(String channelId, boolean enabled) {
        PaymentGatewayProperties.Channel channel = channels.get(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Unknown channel: " + channelId);
        }
        enabledOverrides.put(channelId, enabled);
        return channel;
    }
}
