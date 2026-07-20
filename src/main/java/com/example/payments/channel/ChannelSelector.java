package com.example.payments.channel;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChannelSelector {

    private final ChannelRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    public ChannelSelector(ChannelRegistry registry) {
        this.registry = registry;
    }

    public List<PaymentGatewayProperties.Channel> select(
            PaymentProduct product,
            Collection<String> requestedChannelIds,
            int maxAttempts
    ) {
        return select(product, requestedChannelIds, maxAttempts, null, RoutingMode.ROUND_ROBIN);
    }

    public List<PaymentGatewayProperties.Channel> select(
            PaymentProduct product,
            Collection<String> requestedChannelIds,
            int maxAttempts,
            BigDecimal amount,
            RoutingMode routingMode
    ) {
        Set<String> requestedIds = requestedChannelIds == null
                ? Set.of()
                : new HashSet<>(requestedChannelIds);
        RoutingMode mode = routingMode == null ? RoutingMode.ROUND_ROBIN : routingMode;
        List<PaymentGatewayProperties.Channel> eligible = registry.all().stream()
                .filter(registry::isEnabled)
                .filter(PaymentGatewayProperties.Channel::isDailyEnabled)
                .filter(channel -> requestedIds.isEmpty() || requestedIds.contains(channel.getId()))
                .filter(channel -> product == null || channel.getProducts().isEmpty() || channel.getProducts().contains(product))
                .filter(channel -> amountFits(channel, amount))
                .filter(channel -> Objects.equals("ALIPAY", channel.getProvider())
                        || Objects.equals("ALIPAY_DIRECT", channel.getProvider())
                        || Objects.equals("DOUYIN", channel.getProvider()))
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException("No enabled payment channel matches the request");
        }

        List<PaymentGatewayProperties.Channel> ordered = switch (mode) {
            case PRIORITY -> eligible;
            case RANDOM -> shuffled(eligible);
            case WEIGHTED_RANDOM -> weightedRandom(eligible);
            case ROUND_ROBIN -> roundRobin(product, eligible);
        };
        int attemptLimit = maxAttempts <= 0 ? ordered.size() : Math.min(maxAttempts, ordered.size());
        return ordered.subList(0, attemptLimit);
    }

    private List<PaymentGatewayProperties.Channel> roundRobin(
            PaymentProduct product,
            List<PaymentGatewayProperties.Channel> eligible
    ) {
        List<PaymentGatewayProperties.Channel> weighted = expandByWeight(eligible);
        String cursorKey = product == null ? "generic" : product.name();
        int start = Math.floorMod(cursors.computeIfAbsent(cursorKey, ignored -> new AtomicInteger()).getAndIncrement(), weighted.size());

        List<PaymentGatewayProperties.Channel> rotated = new ArrayList<>(weighted.size());
        for (int i = 0; i < weighted.size(); i++) {
            rotated.add(weighted.get((start + i) % weighted.size()));
        }

        List<PaymentGatewayProperties.Channel> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PaymentGatewayProperties.Channel channel : rotated) {
            if (seen.add(channel.getId())) {
                unique.add(channel);
            }
        }
        return unique;
    }

    private static List<PaymentGatewayProperties.Channel> shuffled(List<PaymentGatewayProperties.Channel> eligible) {
        List<PaymentGatewayProperties.Channel> shuffled = new ArrayList<>(eligible);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    private static List<PaymentGatewayProperties.Channel> weightedRandom(List<PaymentGatewayProperties.Channel> eligible) {
        List<PaymentGatewayProperties.Channel> remaining = new ArrayList<>(eligible);
        List<PaymentGatewayProperties.Channel> result = new ArrayList<>(eligible.size());
        while (!remaining.isEmpty()) {
            int totalWeight = remaining.stream()
                    .mapToInt(channel -> Math.max(1, channel.getWeight()))
                    .sum();
            int target = ThreadLocalRandom.current().nextInt(totalWeight);
            int cursor = 0;
            for (int i = 0; i < remaining.size(); i++) {
                cursor += Math.max(1, remaining.get(i).getWeight());
                if (target < cursor) {
                    result.add(remaining.remove(i));
                    break;
                }
            }
        }
        return result;
    }

    private static boolean amountFits(PaymentGatewayProperties.Channel channel, BigDecimal amount) {
        if (amount == null) {
            return true;
        }
        BigDecimal payMin = channel.getPayMin();
        if (payMin != null && payMin.signum() > 0 && amount.compareTo(payMin) < 0) {
            return false;
        }
        BigDecimal payMax = channel.getPayMax();
        return payMax == null || payMax.signum() <= 0 || amount.compareTo(payMax) <= 0;
    }

    private static List<PaymentGatewayProperties.Channel> expandByWeight(List<PaymentGatewayProperties.Channel> channels) {
        List<PaymentGatewayProperties.Channel> weighted = new ArrayList<>();
        for (PaymentGatewayProperties.Channel channel : channels) {
            int weight = Math.max(1, channel.getWeight());
            for (int i = 0; i < weight; i++) {
                weighted.add(channel);
            }
        }
        return weighted;
    }
}
