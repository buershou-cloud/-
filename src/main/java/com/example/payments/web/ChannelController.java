package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ChannelCreateRequest;
import com.example.payments.domain.ChannelUpdateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.gateway.douyin.DouyinSignatureSupport;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    private static final String PROVIDER_DOUYIN = "DOUYIN";
    private static final String CREDENTIAL_PUBLIC_KEY = "PUBLIC_KEY";
    private static final String CREDENTIAL_CERTIFICATE = "CERTIFICATE";

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
        applyDouyinConfig(channel.getDouyin(), request);
        applyDefaultCallbackUrls(channel, servletRequest);
        validateDirectSubMerchant(channel, channel.isEnabled());
        validateDouyinConfig(channel, channel.isEnabled());
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
        String requestedId = hasText(request.id())
                ? cleanRequired(request.id(), "channel id is required")
                : channelId;
        boolean renamed = !requestedId.equals(channelId);

        if (request.enabled() != null) {
            channelRegistry.setEnabled(channelId, request.enabled());
            channel.setEnabled(request.enabled());
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
        applyDouyinConfig(channel.getDouyin(), request);
        channel.setId(requestedId);
        applyDefaultCallbackUrls(channel, servletRequest);
        validateDirectSubMerchant(channel, channelRegistry.isEnabled(channel));
        validateDouyinConfig(channel, channelRegistry.isEnabled(channel));
        validateAmountRange(channel);
        return view(renamed
                ? channelRegistry.rename(channelId, requestedId, channel)
                : channelRegistry.save(channel));
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
                null,
                null,
                hasText(channel.getAlipay().getMerchantPrivateKey()),
                hasText(channel.getAlipay().getAlipayPublicKey()),
                normalizeCredentialMode(channel.getAlipay().getCredentialMode()),
                channel.getAlipay().getAppCertSn(),
                channel.getAlipay().getAlipayCertSn(),
                channel.getAlipay().getAlipayRootCertSn(),
                hasText(channel.getAlipay().getAppCertContent()),
                hasText(channel.getAlipay().getAlipayCertContent()),
                hasText(channel.getAlipay().getAlipayRootCertContent()),
                channel.getAlipay().getAppAuthToken(),
                hasText(channel.getAlipay().getAppAuthToken()),
                channel.getAlipay().getSubMerchantId(),
                hasText(channel.getAlipay().getSubMerchantId()),
                channel.getAlipay().getNotifyUrl(),
                channel.getAlipay().getReturnUrl(),
                channel.getDouyin().getGatewayUrl(),
                channel.getDouyin().getAppId(),
                channel.getDouyin().getMchId(),
                channel.getDouyin().getMerchantSerialNo(),
                null,
                null,
                null,
                null,
                hasText(channel.getDouyin().getMerchantCertificate()),
                hasText(channel.getDouyin().getMerchantPrivateKey()),
                hasText(channel.getDouyin().getPlatformCertificate()),
                hasText(channel.getDouyin().getEncryptKey()),
                channel.getDouyin().getNotifyUrl(),
                channel.getDouyin().getReturnUrl(),
                channel.getDouyin().getH5AppName()
        );
    }

    private void applyAlipayConfig(PaymentGatewayProperties.Alipay alipay, ChannelCreateRequest request) {
        setIfPresent(request.gatewayUrl(), alipay::setGatewayUrl);
        setIfPresent(request.appId(), alipay::setAppId);
        setIfPresent(request.merchantPrivateKey(), alipay::setMerchantPrivateKey);
        setIfPresent(request.alipayPublicKey(), alipay::setAlipayPublicKey);
        setIfPresent(request.credentialMode(), value -> alipay.setCredentialMode(normalizeCredentialMode(value)));
        setIfPresent(request.appCertSn(), alipay::setAppCertSn);
        setIfPresent(request.alipayCertSn(), alipay::setAlipayCertSn);
        setIfPresent(request.alipayRootCertSn(), alipay::setAlipayRootCertSn);
        setIfPresent(request.appCertContent(), alipay::setAppCertContent);
        setIfPresent(request.alipayCertContent(), alipay::setAlipayCertContent);
        setIfPresent(request.alipayRootCertContent(), alipay::setAlipayRootCertContent);
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
        setIfPresent(request.credentialMode(), value -> alipay.setCredentialMode(normalizeCredentialMode(value)));
        setIfPresent(request.appCertSn(), alipay::setAppCertSn);
        setIfPresent(request.alipayCertSn(), alipay::setAlipayCertSn);
        setIfPresent(request.alipayRootCertSn(), alipay::setAlipayRootCertSn);
        setIfPresent(request.appCertContent(), alipay::setAppCertContent);
        setIfPresent(request.alipayCertContent(), alipay::setAlipayCertContent);
        setIfPresent(request.alipayRootCertContent(), alipay::setAlipayRootCertContent);
        setIfPresent(request.appAuthToken(), alipay::setAppAuthToken);
        setIfPresent(request.subMerchantId(), alipay::setSubMerchantId);
        setIfPresent(request.notifyUrl(), alipay::setNotifyUrl);
        setIfPresent(request.returnUrl(), alipay::setReturnUrl);
    }

    private void applyDouyinConfig(PaymentGatewayProperties.Douyin douyin, ChannelCreateRequest request) {
        setIfPresent(request.douyinGatewayUrl(), douyin::setGatewayUrl);
        setIfPresent(request.douyinAppId(), douyin::setAppId);
        setIfPresent(request.douyinMchId(), douyin::setMchId);
        setIfPresent(request.douyinMerchantSerialNo(), douyin::setMerchantSerialNo);
        applyDouyinMerchantCertificate(douyin, request.douyinMerchantCertificate());
        setIfPresent(request.douyinMerchantPrivateKey(), douyin::setMerchantPrivateKey);
        setIfPresent(request.douyinPlatformCertificate(), douyin::setPlatformCertificate);
        setIfPresent(request.douyinEncryptKey(), douyin::setEncryptKey);
        setIfPresent(request.douyinNotifyUrl(), douyin::setNotifyUrl);
        setIfPresent(request.douyinReturnUrl(), douyin::setReturnUrl);
        setIfPresent(request.douyinH5AppName(), douyin::setH5AppName);
    }

    private void applyDouyinConfig(PaymentGatewayProperties.Douyin douyin, ChannelUpdateRequest request) {
        setIfPresent(request.douyinGatewayUrl(), douyin::setGatewayUrl);
        setIfPresent(request.douyinAppId(), douyin::setAppId);
        setIfPresent(request.douyinMchId(), douyin::setMchId);
        setIfPresent(request.douyinMerchantSerialNo(), douyin::setMerchantSerialNo);
        applyDouyinMerchantCertificate(douyin, request.douyinMerchantCertificate());
        setIfPresent(request.douyinMerchantPrivateKey(), douyin::setMerchantPrivateKey);
        setIfPresent(request.douyinPlatformCertificate(), douyin::setPlatformCertificate);
        setIfPresent(request.douyinEncryptKey(), douyin::setEncryptKey);
        setIfPresent(request.douyinNotifyUrl(), douyin::setNotifyUrl);
        setIfPresent(request.douyinReturnUrl(), douyin::setReturnUrl);
        setIfPresent(request.douyinH5AppName(), douyin::setH5AppName);
    }

    private static void applyDouyinMerchantCertificate(
            PaymentGatewayProperties.Douyin douyin,
            String merchantCertificate
    ) {
        if (!hasText(merchantCertificate)) {
            return;
        }
        String certificate = merchantCertificate.trim();
        douyin.setMerchantCertificate(certificate);
        douyin.setMerchantSerialNo(DouyinSignatureSupport.certificateSerial(certificate));
    }

    private static void applyDefaultCallbackUrls(
            PaymentGatewayProperties.Channel channel,
            HttpServletRequest servletRequest
    ) {
        if (PROVIDER_DOUYIN.equals(channel.getProvider())) {
            if (!hasText(channel.getDouyin().getNotifyUrl())) {
                channel.getDouyin().setNotifyUrl(RequestUrlSupport.douyinNotifyUrl(servletRequest, channel.getId()));
            }
            if (!hasText(channel.getDouyin().getReturnUrl())) {
                channel.getDouyin().setReturnUrl(RequestUrlSupport.douyinReturnUrl(servletRequest, channel.getId()));
            }
            return;
        }
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
        if (!PROVIDER_ALIPAY.equals(provider)
                && !PROVIDER_ALIPAY_DIRECT.equals(provider)
                && !PROVIDER_DOUYIN.equals(provider)) {
            throw new IllegalArgumentException("provider must be ALIPAY, ALIPAY_DIRECT or DOUYIN");
        }
        return provider;
    }

    private static String normalizeCredentialMode(String value) {
        if (!hasText(value)) {
            return CREDENTIAL_PUBLIC_KEY;
        }
        String mode = value.trim().toUpperCase();
        if (!CREDENTIAL_PUBLIC_KEY.equals(mode) && !CREDENTIAL_CERTIFICATE.equals(mode)) {
            throw new IllegalArgumentException("credentialMode must be PUBLIC_KEY or CERTIFICATE");
        }
        return mode;
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

    private static void validateDouyinConfig(PaymentGatewayProperties.Channel channel, boolean enabled) {
        if (!enabled || !PROVIDER_DOUYIN.equals(channel.getProvider())) {
            return;
        }
        PaymentGatewayProperties.Douyin douyin = channel.getDouyin();
        if (!hasText(douyin.getAppId())
                || !hasText(douyin.getMchId())
                || !hasText(douyin.getMerchantSerialNo())
                || !hasText(douyin.getMerchantCertificate())
                || !hasText(douyin.getMerchantPrivateKey())
                || !hasText(douyin.getPlatformCertificate())) {
            throw new IllegalArgumentException("DOUYIN channel requires appId, mchId, merchant API certificate, merchant private key and platform certificate");
        }
        if (!hasText(douyin.getEncryptKey())
                || douyin.getEncryptKey().getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new IllegalArgumentException("Douyin encrypt key must be exactly 32 bytes");
        }
        String merchantSerial = DouyinSignatureSupport.certificateSerial(douyin.getMerchantCertificate());
        if (!merchantSerial.equalsIgnoreCase(douyin.getMerchantSerialNo())) {
            throw new IllegalArgumentException("Douyin merchant certificate serial number does not match the configured serial number");
        }
        if (!DouyinSignatureSupport.privateKeyMatchesCertificate(
                douyin.getMerchantPrivateKey(),
                douyin.getMerchantCertificate()
        )) {
            throw new IllegalArgumentException("Douyin merchant private key does not match the merchant API certificate");
        }
        DouyinSignatureSupport.certificateSerial(douyin.getPlatformCertificate());
        validateHttpsCallback(douyin.getNotifyUrl(), "Douyin notify URL");
    }

    private static void validateHttpsCallback(String value, String name) {
        try {
            URI uri = URI.create(cleanRequired(value, name + " is required"));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null) {
                throw new IllegalArgumentException(name + " must be a direct HTTPS URL without query parameters");
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith(name)) {
                throw ex;
            }
            throw new IllegalArgumentException(name + " is invalid", ex);
        }
    }

    private Set<PaymentProduct> normalizedProducts(
            PaymentGatewayProperties.Channel channel,
            Set<PaymentProduct> products
    ) {
        if (products == null) {
            if (PROVIDER_DOUYIN.equals(channel.getProvider())) {
                return new LinkedHashSet<>(Set.of(PaymentProduct.DOUYIN_H5));
            }
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

        if (PROVIDER_DOUYIN.equals(channel.getProvider())) {
            if (!products.stream().allMatch(PaymentProduct::douyinPaymentProduct)) {
                throw new IllegalArgumentException("DOUYIN channels can only use Douyin payment products");
            }
            return new LinkedHashSet<>(products);
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
            String merchantPrivateKey,
            String alipayPublicKey,
            boolean hasMerchantPrivateKey,
            boolean hasAlipayPublicKey,
            String credentialMode,
            String appCertSn,
            String alipayCertSn,
            String alipayRootCertSn,
            boolean hasAppCertContent,
            boolean hasAlipayCertContent,
            boolean hasAlipayRootCertContent,
            String appAuthToken,
            boolean hasAppAuthToken,
            String subMerchantId,
            boolean hasSubMerchantId,
            String notifyUrl,
            String returnUrl,
            String douyinGatewayUrl,
            String douyinAppId,
            String douyinMchId,
            String douyinMerchantSerialNo,
            String douyinMerchantCertificate,
            String douyinMerchantPrivateKey,
            String douyinPlatformCertificate,
            String douyinEncryptKey,
            boolean hasDouyinMerchantCertificate,
            boolean hasDouyinMerchantPrivateKey,
            boolean hasDouyinPlatformCertificate,
            boolean hasDouyinEncryptKey,
            String douyinNotifyUrl,
            String douyinReturnUrl,
            String douyinH5AppName
    ) {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
