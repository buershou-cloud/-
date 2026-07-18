package com.example.payments.channel;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelRegistry {

    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String DEFAULT_DOUYIN_GATEWAY = "https://api.douyinpay.com";
    private static final String CREDENTIAL_PUBLIC_KEY = "PUBLIC_KEY";

    private final Map<String, PaymentGatewayProperties.Channel> channels;
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public ChannelRegistry(PaymentGatewayProperties properties) {
        this(properties, (JdbcTemplate) null);
    }

    @Autowired
    public ChannelRegistry(PaymentGatewayProperties properties, ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(properties, jdbcTemplateProvider.getIfAvailable());
    }

    private ChannelRegistry(PaymentGatewayProperties properties, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.channels = new ConcurrentHashMap<>(properties.getChannels().stream()
                .collect(Collectors.toMap(
                        PaymentGatewayProperties.Channel::getId,
                        Function.identity(),
                        (left, right) -> right
                )));
        if (databaseBacked()) {
            loadFromDatabase();
        }
    }

    public synchronized List<PaymentGatewayProperties.Channel> all() {
        loadFromDatabase();
        return channels.values().stream()
                .sorted((left, right) -> {
                    int priority = Integer.compare(left.getPriority(), right.getPriority());
                    return priority != 0 ? priority : left.getId().compareTo(right.getId());
                })
                .toList();
    }

    public synchronized Optional<PaymentGatewayProperties.Channel> find(String channelId) {
        loadFromDatabase();
        return Optional.ofNullable(channels.get(channelId));
    }

    public synchronized PaymentGatewayProperties.Channel add(PaymentGatewayProperties.Channel channel) {
        if (channel.getId() == null || channel.getId().isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        if (channels.containsKey(channel.getId())) {
            throw new IllegalArgumentException("Channel already exists: " + channel.getId());
        }
        if (databaseBacked()) {
            insertChannel(channel);
            loadFromDatabase();
            return channels.get(channel.getId());
        }
        channels.put(channel.getId(), channel);
        return channel;
    }

    public synchronized PaymentGatewayProperties.Channel save(PaymentGatewayProperties.Channel channel) {
        if (channel == null || channel.getId() == null || channel.getId().isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        if (!channels.containsKey(channel.getId())) {
            throw new IllegalArgumentException("Unknown channel: " + channel.getId());
        }
        if (databaseBacked()) {
            updateChannel(channel);
            loadFromDatabase();
            return channels.get(channel.getId());
        }
        channels.put(channel.getId(), channel);
        return channel;
    }

    public synchronized PaymentGatewayProperties.Channel rename(
            String currentId,
            String newId,
            PaymentGatewayProperties.Channel channel
    ) {
        if (currentId == null || currentId.isBlank()) {
            throw new IllegalArgumentException("current channel id is required");
        }
        if (newId == null || newId.isBlank()) {
            throw new IllegalArgumentException("channel id is required");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel is required");
        }
        loadFromDatabase();
        if (!channels.containsKey(currentId)) {
            throw new IllegalArgumentException("Unknown channel: " + currentId);
        }
        if (!currentId.equals(newId) && channels.containsKey(newId)) {
            throw new IllegalArgumentException("Channel already exists: " + newId);
        }
        channel.setId(newId);
        if (currentId.equals(newId)) {
            return save(channel);
        }
        if (databaseBacked()) {
            insertChannel(channel);
            moveChannelReferences(currentId, newId);
            jdbcTemplate.update("DELETE FROM pay_channel WHERE id = ?", currentId);
            loadFromDatabase();
            moveEnabledOverride(currentId, newId);
            return channels.get(newId);
        }
        channels.remove(currentId);
        channels.put(newId, channel);
        moveEnabledOverride(currentId, newId);
        return channel;
    }

    public synchronized PaymentGatewayProperties.Channel remove(String channelId) {
        PaymentGatewayProperties.Channel removed = channels.get(channelId);
        if (removed == null) {
            throw new IllegalArgumentException("Unknown channel: " + channelId);
        }
        if (databaseBacked()) {
            jdbcTemplate.update("DELETE FROM pay_channel WHERE id = ?", channelId);
        }
        channels.remove(channelId);
        enabledOverrides.remove(channelId);
        return removed;
    }

    public boolean isEnabled(PaymentGatewayProperties.Channel channel) {
        return enabledOverrides.getOrDefault(channel.getId(), channel.isEnabled());
    }

    public synchronized PaymentGatewayProperties.Channel setEnabled(String channelId, boolean enabled) {
        PaymentGatewayProperties.Channel channel = channels.get(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Unknown channel: " + channelId);
        }
        if (databaseBacked()) {
            jdbcTemplate.update("UPDATE pay_channel SET enabled = ? WHERE id = ?", enabled ? 1 : 0, channelId);
            channel.setEnabled(enabled);
        } else {
            enabledOverrides.put(channelId, enabled);
        }
        return channel;
    }

    private boolean databaseBacked() {
        return jdbcTemplate != null;
    }

    private void loadFromDatabase() {
        if (!databaseBacked()) {
            return;
        }
        List<PaymentGatewayProperties.Channel> loaded = jdbcTemplate.query("""
                SELECT id, provider, enabled, daily_enabled, priority, weight, pay_min, pay_max,
                       gateway_url, app_id, alipay_public_key, merchant_private_key, credential_mode,
                       app_cert_sn, alipay_cert_sn, alipay_root_cert_sn,
                       app_cert_content, alipay_cert_content, alipay_root_cert_content,
                       app_auth_token, sub_merchant_id, notify_url, return_url, charset_name, sign_type,
                       douyin_gateway_url, douyin_app_id, douyin_mch_id, douyin_merchant_serial_no,
                       douyin_merchant_private_key, douyin_platform_certificate, douyin_encrypt_key,
                       douyin_notify_url, douyin_return_url, douyin_h5_app_name
                FROM pay_channel
                """, (rs, rowNum) -> {
            PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
            channel.setId(rs.getString("id"));
            channel.setProvider(rs.getString("provider"));
            channel.setEnabled(rs.getBoolean("enabled"));
            channel.setDailyEnabled(rs.getBoolean("daily_enabled"));
            channel.setPriority(rs.getInt("priority"));
            channel.setWeight(rs.getInt("weight"));
            channel.setPayMin(rs.getBigDecimal("pay_min"));
            channel.setPayMax(rs.getBigDecimal("pay_max"));
            PaymentGatewayProperties.Alipay alipay = channel.getAlipay();
            alipay.setGatewayUrl(firstText(rs.getString("gateway_url"), DEFAULT_GATEWAY));
            alipay.setAppId(nullIfBlank(rs.getString("app_id")));
            alipay.setAlipayPublicKey(nullIfBlank(rs.getString("alipay_public_key")));
            alipay.setMerchantPrivateKey(nullIfBlank(rs.getString("merchant_private_key")));
            alipay.setCredentialMode(normalizeCredentialMode(rs.getString("credential_mode")));
            alipay.setAppCertSn(nullIfBlank(rs.getString("app_cert_sn")));
            alipay.setAlipayCertSn(nullIfBlank(rs.getString("alipay_cert_sn")));
            alipay.setAlipayRootCertSn(nullIfBlank(rs.getString("alipay_root_cert_sn")));
            alipay.setAppCertContent(nullIfBlank(rs.getString("app_cert_content")));
            alipay.setAlipayCertContent(nullIfBlank(rs.getString("alipay_cert_content")));
            alipay.setAlipayRootCertContent(nullIfBlank(rs.getString("alipay_root_cert_content")));
            alipay.setAppAuthToken(nullIfBlank(rs.getString("app_auth_token")));
            alipay.setSubMerchantId(nullIfBlank(rs.getString("sub_merchant_id")));
            alipay.setNotifyUrl(nullIfBlank(rs.getString("notify_url")));
            alipay.setReturnUrl(nullIfBlank(rs.getString("return_url")));
            alipay.setCharset(firstText(rs.getString("charset_name"), "UTF-8"));
            alipay.setSignType(firstText(rs.getString("sign_type"), "RSA2"));
            PaymentGatewayProperties.Douyin douyin = channel.getDouyin();
            douyin.setGatewayUrl(firstText(rs.getString("douyin_gateway_url"), DEFAULT_DOUYIN_GATEWAY));
            douyin.setAppId(nullIfBlank(rs.getString("douyin_app_id")));
            douyin.setMchId(nullIfBlank(rs.getString("douyin_mch_id")));
            douyin.setMerchantSerialNo(nullIfBlank(rs.getString("douyin_merchant_serial_no")));
            douyin.setMerchantPrivateKey(nullIfBlank(rs.getString("douyin_merchant_private_key")));
            douyin.setPlatformCertificate(nullIfBlank(rs.getString("douyin_platform_certificate")));
            douyin.setEncryptKey(nullIfBlank(rs.getString("douyin_encrypt_key")));
            douyin.setNotifyUrl(nullIfBlank(rs.getString("douyin_notify_url")));
            douyin.setReturnUrl(nullIfBlank(rs.getString("douyin_return_url")));
            douyin.setH5AppName(firstText(rs.getString("douyin_h5_app_name"), "支付平台"));
            channel.setProducts(loadProducts(channel.getId()));
            return channel;
        });
        channels.clear();
        loaded.forEach(channel -> channels.put(channel.getId(), channel));
    }

    private Set<PaymentProduct> loadProducts(String channelId) {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT product FROM pay_channel_product WHERE channel_id = ? ORDER BY product",
                String.class,
                channelId
        );
        LinkedHashSet<PaymentProduct> products = new LinkedHashSet<>();
        for (String value : values) {
            try {
                products.add(PaymentProduct.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // Ignore historical/unknown product codes so one bad row does not break boot.
            }
        }
        return products;
    }

    private void insertChannel(PaymentGatewayProperties.Channel channel) {
        jdbcTemplate.update("""
                INSERT INTO pay_channel (
                  id, provider, enabled, daily_enabled, priority, weight, pay_min, pay_max,
                  gateway_url, app_id, alipay_public_key, merchant_private_key, credential_mode,
                  app_cert_sn, alipay_cert_sn, alipay_root_cert_sn,
                  app_cert_content, alipay_cert_content, alipay_root_cert_content,
                  app_auth_token, sub_merchant_id, notify_url, return_url, charset_name, sign_type,
                  douyin_gateway_url, douyin_app_id, douyin_mch_id, douyin_merchant_serial_no,
                  douyin_merchant_private_key, douyin_platform_certificate, douyin_encrypt_key,
                  douyin_notify_url, douyin_return_url, douyin_h5_app_name
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                channel.getId(),
                firstText(channel.getProvider(), "ALIPAY"),
                channel.isEnabled() ? 1 : 0,
                channel.isDailyEnabled() ? 1 : 0,
                channel.getPriority(),
                channel.getWeight(),
                firstAmount(channel.getPayMin(), BigDecimal.ZERO),
                firstAmount(channel.getPayMax(), new BigDecimal("50000.00")),
                firstText(channel.getAlipay().getGatewayUrl(), DEFAULT_GATEWAY),
                firstText(channel.getAlipay().getAppId(), ""),
                nullIfBlank(channel.getAlipay().getAlipayPublicKey()),
                nullIfBlank(channel.getAlipay().getMerchantPrivateKey()),
                normalizeCredentialMode(channel.getAlipay().getCredentialMode()),
                nullIfBlank(channel.getAlipay().getAppCertSn()),
                nullIfBlank(channel.getAlipay().getAlipayCertSn()),
                nullIfBlank(channel.getAlipay().getAlipayRootCertSn()),
                nullIfBlank(channel.getAlipay().getAppCertContent()),
                nullIfBlank(channel.getAlipay().getAlipayCertContent()),
                nullIfBlank(channel.getAlipay().getAlipayRootCertContent()),
                nullIfBlank(channel.getAlipay().getAppAuthToken()),
                nullIfBlank(channel.getAlipay().getSubMerchantId()),
                nullIfBlank(channel.getAlipay().getNotifyUrl()),
                nullIfBlank(channel.getAlipay().getReturnUrl()),
                firstText(channel.getAlipay().getCharset(), "UTF-8"),
                firstText(channel.getAlipay().getSignType(), "RSA2"),
                firstText(channel.getDouyin().getGatewayUrl(), DEFAULT_DOUYIN_GATEWAY),
                nullIfBlank(channel.getDouyin().getAppId()),
                nullIfBlank(channel.getDouyin().getMchId()),
                nullIfBlank(channel.getDouyin().getMerchantSerialNo()),
                nullIfBlank(channel.getDouyin().getMerchantPrivateKey()),
                nullIfBlank(channel.getDouyin().getPlatformCertificate()),
                nullIfBlank(channel.getDouyin().getEncryptKey()),
                nullIfBlank(channel.getDouyin().getNotifyUrl()),
                nullIfBlank(channel.getDouyin().getReturnUrl()),
                firstText(channel.getDouyin().getH5AppName(), "支付平台")
        );
        replaceProducts(channel);
    }

    private void updateChannel(PaymentGatewayProperties.Channel channel) {
        jdbcTemplate.update("""
                UPDATE pay_channel
                SET provider = ?, enabled = ?, daily_enabled = ?, priority = ?, weight = ?,
                    pay_min = ?, pay_max = ?, gateway_url = ?, app_id = ?,
                    alipay_public_key = ?, merchant_private_key = ?, credential_mode = ?,
                    app_cert_sn = ?, alipay_cert_sn = ?, alipay_root_cert_sn = ?,
                    app_cert_content = ?, alipay_cert_content = ?, alipay_root_cert_content = ?,
                    app_auth_token = ?, sub_merchant_id = ?, notify_url = ?, return_url = ?,
                    charset_name = ?, sign_type = ?,
                    douyin_gateway_url = ?, douyin_app_id = ?, douyin_mch_id = ?,
                    douyin_merchant_serial_no = ?, douyin_merchant_private_key = ?,
                    douyin_platform_certificate = ?, douyin_encrypt_key = ?,
                    douyin_notify_url = ?, douyin_return_url = ?, douyin_h5_app_name = ?
                WHERE id = ?
                """,
                firstText(channel.getProvider(), "ALIPAY"),
                channel.isEnabled() ? 1 : 0,
                channel.isDailyEnabled() ? 1 : 0,
                channel.getPriority(),
                channel.getWeight(),
                firstAmount(channel.getPayMin(), BigDecimal.ZERO),
                firstAmount(channel.getPayMax(), new BigDecimal("50000.00")),
                firstText(channel.getAlipay().getGatewayUrl(), DEFAULT_GATEWAY),
                firstText(channel.getAlipay().getAppId(), ""),
                nullIfBlank(channel.getAlipay().getAlipayPublicKey()),
                nullIfBlank(channel.getAlipay().getMerchantPrivateKey()),
                normalizeCredentialMode(channel.getAlipay().getCredentialMode()),
                nullIfBlank(channel.getAlipay().getAppCertSn()),
                nullIfBlank(channel.getAlipay().getAlipayCertSn()),
                nullIfBlank(channel.getAlipay().getAlipayRootCertSn()),
                nullIfBlank(channel.getAlipay().getAppCertContent()),
                nullIfBlank(channel.getAlipay().getAlipayCertContent()),
                nullIfBlank(channel.getAlipay().getAlipayRootCertContent()),
                nullIfBlank(channel.getAlipay().getAppAuthToken()),
                nullIfBlank(channel.getAlipay().getSubMerchantId()),
                nullIfBlank(channel.getAlipay().getNotifyUrl()),
                nullIfBlank(channel.getAlipay().getReturnUrl()),
                firstText(channel.getAlipay().getCharset(), "UTF-8"),
                firstText(channel.getAlipay().getSignType(), "RSA2"),
                firstText(channel.getDouyin().getGatewayUrl(), DEFAULT_DOUYIN_GATEWAY),
                nullIfBlank(channel.getDouyin().getAppId()),
                nullIfBlank(channel.getDouyin().getMchId()),
                nullIfBlank(channel.getDouyin().getMerchantSerialNo()),
                nullIfBlank(channel.getDouyin().getMerchantPrivateKey()),
                nullIfBlank(channel.getDouyin().getPlatformCertificate()),
                nullIfBlank(channel.getDouyin().getEncryptKey()),
                nullIfBlank(channel.getDouyin().getNotifyUrl()),
                nullIfBlank(channel.getDouyin().getReturnUrl()),
                firstText(channel.getDouyin().getH5AppName(), "支付平台"),
                channel.getId()
        );
        replaceProducts(channel);
    }

    private void replaceProducts(PaymentGatewayProperties.Channel channel) {
        jdbcTemplate.update("DELETE FROM pay_channel_product WHERE channel_id = ?", channel.getId());
        for (PaymentProduct product : channel.getProducts()) {
            jdbcTemplate.update(
                    "INSERT INTO pay_channel_product (channel_id, product) VALUES (?, ?)",
                    channel.getId(),
                    product.name()
            );
        }
    }

    private void moveChannelReferences(String currentId, String newId) {
        jdbcTemplate.update("UPDATE merchant_channel SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE pay_order SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE payment_attempt SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE refund_order SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE profit_sharing_order SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE onboarding_record SET channel_id = ? WHERE channel_id = ?", newId, currentId);
        jdbcTemplate.update("UPDATE complaint_record SET channel_id = ? WHERE channel_id = ?", newId, currentId);
    }

    private void moveEnabledOverride(String currentId, String newId) {
        Boolean enabled = enabledOverrides.remove(currentId);
        if (enabled != null) {
            enabledOverrides.put(newId, enabled);
        }
    }

    private static BigDecimal firstAmount(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String nullIfBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String normalizeCredentialMode(String value) {
        return "CERTIFICATE".equalsIgnoreCase(firstText(value, CREDENTIAL_PUBLIC_KEY))
                ? "CERTIFICATE"
                : CREDENTIAL_PUBLIC_KEY;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
