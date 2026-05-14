package com.example.payments.channel;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelSelectorTest {

    @Test
    void rotatesEligibleChannelsForSameProduct() {
        PaymentGatewayProperties properties = properties(
                channel("ali-a", true, 10),
                channel("ali-b", true, 20)
        );
        ChannelSelector selector = new ChannelSelector(new ChannelRegistry(properties));

        List<PaymentGatewayProperties.Channel> first = selector.select(PaymentProduct.ALIPAY_WAP, List.of(), 1);
        List<PaymentGatewayProperties.Channel> second = selector.select(PaymentProduct.ALIPAY_WAP, List.of(), 1);

        assertThat(first).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-a");
        assertThat(second).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-b");
    }

    @Test
    void filtersDisabledChannels() {
        PaymentGatewayProperties properties = properties(
                channel("ali-a", false, 10),
                channel("ali-b", true, 20)
        );
        ChannelSelector selector = new ChannelSelector(new ChannelRegistry(properties));

        List<PaymentGatewayProperties.Channel> selected = selector.select(PaymentProduct.ALIPAY_WAP, List.of(), 2);

        assertThat(selected).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-b");
    }

    @Test
    void filtersChannelsOutsideAmountLimit() {
        PaymentGatewayProperties.Channel small = channel("ali-small", true, 10);
        small.setPayMax(new BigDecimal("10.00"));
        PaymentGatewayProperties.Channel large = channel("ali-large", true, 20);
        large.setPayMin(new BigDecimal("10.01"));
        PaymentGatewayProperties properties = properties(small, large);
        ChannelSelector selector = new ChannelSelector(new ChannelRegistry(properties));

        List<PaymentGatewayProperties.Channel> selected = selector.select(
                PaymentProduct.ALIPAY_WAP,
                List.of(),
                2,
                new BigDecimal("99.00"),
                RoutingMode.PRIORITY
        );

        assertThat(selected).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-large");
    }

    @Test
    void priorityModeKeepsConfiguredPriorityOrder() {
        PaymentGatewayProperties properties = properties(
                channel("ali-b", true, 20),
                channel("ali-a", true, 10)
        );
        ChannelSelector selector = new ChannelSelector(new ChannelRegistry(properties));

        List<PaymentGatewayProperties.Channel> selected = selector.select(
                PaymentProduct.ALIPAY_WAP,
                List.of(),
                2,
                null,
                RoutingMode.PRIORITY
        );

        assertThat(selected).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-a", "ali-b");
    }

    @Test
    void directPaymentProductSelectsDirectChannelOnly() {
        PaymentGatewayProperties.Channel standard = channel("ali-standard", true, 10);
        PaymentGatewayProperties.Channel direct = channel("ali-direct", true, 20);
        direct.setProvider("ALIPAY_DIRECT");
        direct.setProducts(Set.of(PaymentProduct.ALIPAY_DIRECT, PaymentProduct.ALIPAY_DIRECT_WAP));
        PaymentGatewayProperties properties = properties(standard, direct);
        ChannelSelector selector = new ChannelSelector(new ChannelRegistry(properties));

        List<PaymentGatewayProperties.Channel> selected = selector.select(
                PaymentProduct.ALIPAY_DIRECT_WAP,
                List.of(),
                2,
                new BigDecimal("99.00"),
                RoutingMode.PRIORITY
        );

        assertThat(selected).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali-direct");
    }

    private static PaymentGatewayProperties properties(PaymentGatewayProperties.Channel... channels) {
        PaymentGatewayProperties properties = new PaymentGatewayProperties();
        properties.setChannels(List.of(channels));
        return properties;
    }

    private static PaymentGatewayProperties.Channel channel(String id, boolean enabled, int priority) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId(id);
        channel.setEnabled(enabled);
        channel.setPriority(priority);
        channel.setProvider("ALIPAY");
        channel.setProducts(Set.of(PaymentProduct.ALIPAY_WAP));
        return channel;
    }
}
