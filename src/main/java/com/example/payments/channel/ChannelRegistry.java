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
            if (channels.isEmpty()) {
                properties.getChannels().forEach(this::insertChannel);
                loadFromDatabase();
            }
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
                       gateway_url, app_id, alipay_public_key, merchant_private_key, app_auth_token,
                       sub_merchant_id, notify_url, return_url, charset_name, sign_type
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
            alipay.setAppAuthToken(nullIfBlank(rs.getString("app_auth_token")));
            alipay.setSubMerchantId(nullIfBlank(rs.getString("sub_merchant_id")));
            alipay.setNotifyUrl(nullIfBlank(rs.getString("notify_url")));
            alipay.setReturnUrl(nullIfBlank(rs.getString("return_url")));
            alipay.setCharset(firstText(rs.getString("charset_name"), "UTF-8"));
            alipay.setSignType(firstText(rs.getString("sign_type"), "RSA2"));
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
                  gateway_url, app_id, alipay_public_key, merchant_private_key, app_auth_token,
                  sub_merchant_id, notify_url, return_url, charset_name, sign_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                nullIfBlank(channel.getAlipay().getAppAuthToken()),
                nullIfBlank(channel.getAlipay().getSubMerchantId()),
                nullIfBlank(channel.getAlipay().getNotifyUrl()),
                nullIfBlank(channel.getAlipay().getReturnUrl()),
                firstText(channel.getAlipay().getCharset(), "UTF-8"),
                firstText(channel.getAlipay().getSignType(), "RSA2")
        );
        replaceProducts(channel);
    }

    private void updateChannel(PaymentGatewayProperties.Channel channel) {
        jdbcTemplate.update("""
                UPDATE pay_channel
                SET provider = ?, enabled = ?, daily_enabled = ?, priority = ?, weight = ?,
                    pay_min = ?, pay_max = ?, gateway_url = ?, app_id = ?,
                    alipay_public_key = ?, merchant_private_key = ?, app_auth_token = ?,
                    sub_merchant_id = ?, notify_url = ?, return_url = ?, charset_name = ?, sign_type = ?
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
                nullIfBlank(channel.getAlipay().getAppAuthToken()),
                nullIfBlank(channel.getAlipay().getSubMerchantId()),
                nullIfBlank(channel.getAlipay().getNotifyUrl()),
                nullIfBlank(channel.getAlipay().getReturnUrl()),
                firstText(channel.getAlipay().getCharset(), "UTF-8"),
                firstText(channel.getAlipay().getSignType(), "RSA2"),
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

    private static BigDecimal firstAmount(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static String nullIfBlank(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
