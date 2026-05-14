package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ChannelCreateRequest;
import com.example.payments.domain.ChannelUpdateRequest;
import com.example.payments.domain.PaymentProduct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    private final ChannelRegistry channelRegistry;
    private static final String PROVIDER_ALIPAY = "ALIPAY";
    private static final String PROVIDER_ALIPAY_DIRECT = "ALIPAY_DIRECT";

    public ChannelController(ChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @GetMapping
    public List<ChannelView> list() {
        return channelRegistry.all().stream()
                .map(this::view)
                .toList();
    }

    @PostMapping
    public ChannelView create(@RequestBody ChannelCreateRequest request, HttpServletRequest servletRequest) {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        String id = cleanRequired(request.id(), "channel id is required");
        channel.setId(id);
        channel.setProvider(provider(request.provider()));
        channel.setEnabled(request.enabled() == null || request.enabled());
        channel.setDailyEnabled(request.dailyEnabled() == null || request.dailyEnabled());
        channel.setPriority(validPriority(request.priority() == null ? 100 : request.priority()));
        channel.setWeight(validWeight(request.weight() == null ? 1 : request.weight()));
        channel.setPayMin(validAmount(request.payMin(), "payMin"));
        channel.setPayMax(validAmount(request.payMax(), "payMax"));
        channel.setProducts(normalizedProducts(channel, request.products()));
        applyAlipayConfig(channel.getAlipay(), request);
        applyDefaultCallbackUrls(channel, servletRequest);
        validateDirectSubMerchant(channel, channel.isEnabled());
        validateAmountRange(channel);
        return view(channelRegistry.add(channel));
    }

    @PatchMapping("/{channelId}")
    public ChannelView update(
            @PathVariable String channelId,
            @RequestBody ChannelUpdateRequest request,
            HttpServletRequest servletRequest
    ) {
        PaymentGatewayProperties.Channel channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));

        if (request.enabled() != null) {
            channelRegistry.setEnabled(channelId, request.enabled());
        }
        if (request.dailyEnabled() != null) {
            channel.setDailyEnabled(request.dailyEnabled());
        }
        if (request.priority() != null) {
            channel.setPriority(validPriority(request.priority()));
        }
        if (request.weight() != null) {
            channel.setWeight(validWeight(request.weight()));
        }
        if (request.payMin() != null) {
            channel.setPayMin(validAmount(request.payMin(), "payMin"));
        }
        if (request.payMax() != null) {
            channel.setPayMax(validAmount(request.payMax(), "payMax"));
        }
        if (request.products() != null) {
            channel.setProducts(normalizedProducts(channel, request.products()));
        }
        applyAlipayConfig(channel.getAlipay(), request);
        applyDefaultCallbackUrls(channel, servletRequest);
        validateDirectSubMerchant(channel, channelRegistry.isEnabled(channel));
        validateAmountRange(channel);
        return view(channel);
    }

    @DeleteMapping("/{channelId}")
    public ChannelView delete(@PathVariable String channelId) {
        return view(channelRegistry.remove(channelId));
    }

    private ChannelView view(PaymentGatewayProperties.Channel channel) {
        return new ChannelView(
                channel.getId(),
                channel.getProvider(),
                channelRegistry.isEnabled(channel),
                channel.isDailyEnabled(),
                channel.getPriority(),
                channel.getWeight(),
                channel.getPayMin(),
                channel.getPayMax(),
                channel.getProducts(),
                channel.getAlipay().getGatewayUrl(),
                channel.getAlipay().getAppId(),
                hasText(channel.getAlipay().getMerchantPrivateKey()),
                hasText(channel.getAlipay().getAlipayPublicKey()),
                hasText(channel.getAlipay().getAppAuthToken()),
                channel.getAlipay().getSubMerchantId(),
                hasText(channel.getAlipay().getSubMerchantId()),
                channel.getAlipay().getNotifyUrl(),
                channel.getAlipay().getReturnUrl()
        );
    }

    private void applyAlipayConfig(PaymentGatewayProperties.Alipay alipay, ChannelCreateRequest request) {
        setIfPresent(request.gatewayUrl(), alipay::setGatewayUrl);
        setIfPresent(request.appId(), alipay::setAppId);
        setIfPresent(request.merchantPrivateKey(), alipay::setMerchantPrivateKey);
        setIfPresent(request.alipayPublicKey(), alipay::setAlipayPublicKey);
        setIfPresent(request.appAuthToken(), alipay::setAppAuthToken);
        setIfPresent(request.subMerchantId(), alipay::setSubMerchantId);
        setIfPresent(request.notifyUrl(), alipay::setNotifyUrl);
        setIfPresent(request.returnUrl(), alipay::setReturnUrl);
    }

    private void applyAlipayConfig(PaymentGatewayProperties.Alipay alipay, ChannelUpdateRequest request) {
        setIfPresent(request.gatewayUrl(), alipay::setGatewayUrl);
        setIfPresent(request.appId(), alipay::setAppId);
        setIfPresent(request.merchantPrivateKey(), alipay::setMerchantPrivateKey);
        setIfPresent(request.alipayPublicKey(), alipay::setAlipayPublicKey);
        setIfPresent(request.appAuthToken(), alipay::setAppAuthToken);
        setIfPresent(request.subMerchantId(), alipay::setSubMerchantId);
        setIfPresent(request.notifyUrl(), alipay::setNotifyUrl);
        setIfPresent(request.returnUrl(), alipay::setReturnUrl);
    }

    private static void applyDefaultCallbackUrls(
            PaymentGatewayProperties.Channel channel,
            HttpServletRequest servletRequest
    ) {
        if (!hasText(channel.getAlipay().getNotifyUrl())) {
            channel.getAlipay().setNotifyUrl(RequestUrlSupport.alipayNotifyUrl(servletRequest, channel.getId()));
        }
        if (!hasText(channel.getAlipay().getReturnUrl())) {
            channel.getAlipay().setReturnUrl(RequestUrlSupport.alipayReturnUrl(servletRequest, channel.getId()));
        }
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null) {
            setter.accept(value.trim());
        }
    }

    private static String provider(String value) {
        String provider = hasText(value) ? value.trim() : PROVIDER_ALIPAY;
        if (!PROVIDER_ALIPAY.equals(provider) && !PROVIDER_ALIPAY_DIRECT.equals(provider)) {
            throw new IllegalArgumentException("provider must be ALIPAY or ALIPAY_DIRECT");
        }
        return provider;
    }

    private static String cleanRequired(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static int validPriority(int priority) {
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be greater than or equal to 0");
        }
        return priority;
    }

    private static int validWeight(int weight) {
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be greater than or equal to 1");
        }
        return weight;
    }

    private static BigDecimal validAmount(BigDecimal amount, String name) {
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must be greater than or equal to 0");
        }
        return amount;
    }

    private static void validateAmountRange(PaymentGatewayProperties.Channel channel) {
        if (channel.getPayMin() != null
                && channel.getPayMax() != null
                && channel.getPayMax().signum() > 0
                && channel.getPayMin().compareTo(channel.getPayMax()) > 0) {
            throw new IllegalArgumentException("payMin must be less than or equal to payMax");
        }
    }

    private static void validateDirectSubMerchant(PaymentGatewayProperties.Channel channel, boolean enabled) {
        if (enabled
                && PROVIDER_ALIPAY_DIRECT.equals(channel.getProvider())
                && !hasText(channel.getAlipay().getSubMerchantId())) {
            throw new IllegalArgumentException("ALIPAY_DIRECT channel requires subMerchantId (SMID)");
        }
    }

    private Set<PaymentProduct> normalizedProducts(
            PaymentGatewayProperties.Channel channel,
            Set<PaymentProduct> products
    ) {
        if (products == null) {
            return PROVIDER_ALIPAY_DIRECT.equals(channel.getProvider())
                    ? new LinkedHashSet<>(Set.of(PaymentProduct.ALIPAY_DIRECT, PaymentProduct.ALIPAY_DIRECT_WAP))
                    : new LinkedHashSet<>(Set.of(PaymentProduct.ALIPAY_WAP));
        }
        if (products.isEmpty() || products.contains(null)) {
            throw new IllegalArgumentException("products must contain at least one payment product");
        }

        if (PROVIDER_ALIPAY_DIRECT.equals(channel.getProvider())) {
            if (!products.stream().allMatch(PaymentProduct::directPaymentProduct)) {
                throw new IllegalArgumentException("ALIPAY_DIRECT channels can only use direct-payment products");
            }
            EnumSet<PaymentProduct> normalized = EnumSet.copyOf(products);
            normalized.add(PaymentProduct.ALIPAY_DIRECT);
            return new LinkedHashSet<>(normalized);
        }

        if (!products.stream().allMatch(PaymentProduct::standardPaymentProduct)) {
            throw new IllegalArgumentException("ALIPAY channels can only use standard Alipay payment products");
        }
        return new LinkedHashSet<>(products);
    }

    public record ChannelView(
            String id,
            String provider,
            boolean enabled,
            boolean dailyEnabled,
            int priority,
            int weight,
            BigDecimal payMin,
            BigDecimal payMax,
            Set<PaymentProduct> products,
            String gatewayUrl,
            String appId,
            boolean hasMerchantPrivateKey,
            boolean hasAlipayPublicKey,
            boolean hasAppAuthToken,
            String subMerchantId,
            boolean hasSubMerchantId,
            String notifyUrl,
            String returnUrl
    ) {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
