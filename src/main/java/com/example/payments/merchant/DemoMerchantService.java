package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DemoMerchantService {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RsaKeyPair PLATFORM_RSA = rsaKeyPair();
    private static final String DEFAULT_STATUS = "正常";
    private static final String DEFAULT_SETTLEMENT_STATUS = "待结算";

    private final Map<String, DemoMerchant> merchants = new LinkedHashMap<>();
    private final JdbcTemplate jdbcTemplate;

    public DemoMerchantService() {
        this((JdbcTemplate) null);
    }

    @Autowired
    public DemoMerchantService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(jdbcTemplateProvider.getIfAvailable());
    }

    private DemoMerchantService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        if (!databaseBacked()) {
            seedMemory();
        }
    }

    public synchronized List<DemoMerchantView> list() {
        if (databaseBacked()) {
            return loadMerchants().stream().map(DemoMerchantView::from).toList();
        }
        return merchants.values().stream()
                .map(DemoMerchantView::from)
                .toList();
    }

    public synchronized DemoMerchantView create(DemoMerchantCreateRequest request) {
        String merchantId = hasText(request.merchantId())
                ? request.merchantId().trim()
                : "M" + LocalDateTime.now().format(ID_TIME);
        if (databaseBacked()) {
            if (exists(merchantId)) {
                throw new IllegalArgumentException("Merchant already exists: " + merchantId);
            }
            DemoMerchant merchant = merchant(
                    merchantId,
                    required(request.name(), "merchant name is required"),
                    request.feeRate() == null ? new BigDecimal("0.60") : request.feeRate(),
                    hasText(request.status()) ? request.status().trim() : DEFAULT_STATUS,
                    request.todayAmount() == null ? BigDecimal.ZERO : request.todayAmount(),
                    request.channelIds(),
                    request.routingMode()
            );
            insertMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        if (merchants.containsKey(merchantId)) {
            throw new IllegalArgumentException("鍟嗘埛鍙峰凡瀛樺湪: " + merchantId);
        }
        DemoMerchant merchant = merchant(
                merchantId,
                required(request.name(), "鍟嗘埛鍚嶇О涓嶈兘涓虹┖"),
                request.feeRate() == null ? new BigDecimal("0.60") : request.feeRate(),
                hasText(request.status()) ? request.status().trim() : "姝ｅ父",
                request.todayAmount() == null ? BigDecimal.ZERO : request.todayAmount(),
                request.channelIds(),
                request.routingMode()
        );
        merchants.put(merchant.getMerchantId(), merchant);
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView update(String merchantId, DemoMerchantUpdateRequest request) {
        DemoMerchant merchant = merchant(merchantId);
        if (hasText(request.name())) {
            merchant.setName(request.name().trim());
        }
        if (request.feeRate() != null) {
            merchant.setFeeRate(request.feeRate());
        }
        if (hasText(request.status())) {
            merchant.setStatus(request.status().trim());
        }
        if (request.todayAmount() != null) {
            merchant.setTodayAmount(request.todayAmount());
        }
        if (request.channelIds() != null) {
            merchant.setChannelIds(request.channelIds());
        }
        if (request.routingMode() != null) {
            merchant.setRoutingMode(request.routingMode());
        }
        if (databaseBacked()) {
            updateMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView detail(String merchantId) {
        return DemoMerchantView.from(merchant(merchantId));
    }

    public synchronized DemoMerchantView delete(String merchantId) {
        DemoMerchant removed = merchant(merchantId);
        if (databaseBacked()) {
            jdbcTemplate.update("DELETE FROM merchant WHERE merchant_id = ?", merchantId);
            return DemoMerchantView.from(removed);
        }
        removed = merchants.remove(merchantId);
        if (removed == null) {
            throw new IllegalArgumentException("鍟嗘埛涓嶅瓨鍦? " + merchantId);
        }
        return DemoMerchantView.from(removed);
    }

    public synchronized DemoMerchantView settle(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setSettlementStatus(databaseBacked() ? DEFAULT_SETTLEMENT_STATUS.replace("待", "已") : "宸茬粨绠?");
        if (databaseBacked()) {
            updateMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView resetMd5(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setMd5Key(md5Key());
        if (databaseBacked()) {
            updateMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView generateRsa2(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        RsaKeyPair rsa = rsaKeyPair();
        merchant.setRsa2PublicKey(rsa.publicKey());
        merchant.setRsa2PrivateKey(rsa.privateKey());
        if (databaseBacked()) {
            updateMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView updateSignMode(String merchantId, String signMode) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setSignMode(hasText(signMode) ? signMode.trim() : "MD5_RSA2");
        if (databaseBacked()) {
            updateMerchant(merchant);
            return DemoMerchantView.from(readMerchant(merchantId));
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView login(String merchantId, String md5Key, boolean demoLogin) {
        DemoMerchant merchant = merchant(merchantId);
        if (!demoLogin && !merchant.getMd5Key().equals(md5Key)) {
            throw new IllegalArgumentException("鍟嗘埛鍙锋垨 MD5 瀵嗛挜涓嶆纭?");
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized MerchantRouting routing(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        return new MerchantRouting(merchant.getChannelIds(), merchant.getRoutingMode());
    }

    private boolean databaseBacked() {
        return jdbcTemplate != null;
    }

    private void seedMemory() {
    }

    private List<DemoMerchant> loadMerchants() {
        return jdbcTemplate.query("""
                SELECT merchant_id, name, fee_rate, status, settlement_status, md5_key,
                       platform_public_key, rsa2_public_key, rsa2_private_key, sign_mode, routing_mode
                FROM merchant
                ORDER BY merchant_id
                """, (rs, rowNum) -> new DemoMerchant(
                rs.getString("merchant_id"),
                rs.getString("name"),
                rs.getBigDecimal("fee_rate"),
                rs.getString("status"),
                merchantAmount(rs.getString("merchant_id")),
                rs.getString("md5_key"),
                firstText(rs.getString("platform_public_key"), PLATFORM_RSA.publicKey()),
                rs.getString("rsa2_public_key"),
                rs.getString("rsa2_private_key"),
                firstText(rs.getString("settlement_status"), DEFAULT_SETTLEMENT_STATUS),
                firstText(rs.getString("sign_mode"), "MD5_RSA2"),
                loadMerchantChannels(rs.getString("merchant_id")),
                routingMode(rs.getString("routing_mode"))
        ));
    }

    private DemoMerchant readMerchant(String merchantId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT merchant_id, name, fee_rate, status, settlement_status, md5_key,
                           platform_public_key, rsa2_public_key, rsa2_private_key, sign_mode, routing_mode
                    FROM merchant
                    WHERE merchant_id = ?
                    """, (rs, rowNum) -> new DemoMerchant(
                    rs.getString("merchant_id"),
                    rs.getString("name"),
                    rs.getBigDecimal("fee_rate"),
                    rs.getString("status"),
                    merchantAmount(rs.getString("merchant_id")),
                    rs.getString("md5_key"),
                    firstText(rs.getString("platform_public_key"), PLATFORM_RSA.publicKey()),
                    rs.getString("rsa2_public_key"),
                    rs.getString("rsa2_private_key"),
                    firstText(rs.getString("settlement_status"), DEFAULT_SETTLEMENT_STATUS),
                    firstText(rs.getString("sign_mode"), "MD5_RSA2"),
                    loadMerchantChannels(rs.getString("merchant_id")),
                    routingMode(rs.getString("routing_mode"))
            ), merchantId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalArgumentException("Merchant does not exist: " + merchantId);
        }
    }

    private BigDecimal merchantAmount(String merchantId) {
        BigDecimal amount = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM pay_order
                WHERE merchant_id = ? AND status IN ('COMPLETED', 'FROZEN')
                """, BigDecimal.class, merchantId);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private Set<String> loadMerchantChannels(String merchantId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT channel_id FROM merchant_channel WHERE merchant_id = ? ORDER BY created_at, channel_id",
                String.class,
                merchantId
        ));
    }

    private boolean exists(String merchantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant WHERE merchant_id = ?",
                Integer.class,
                merchantId
        );
        return count != null && count > 0;
    }

    private void insertMerchant(DemoMerchant merchant) {
        jdbcTemplate.update("""
                INSERT INTO merchant (
                    merchant_id, name, fee_rate, status, settlement_status, md5_key,
                    platform_public_key, rsa2_public_key, rsa2_private_key, sign_mode, routing_mode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                merchant.getMerchantId(),
                merchant.getName(),
                merchant.getFeeRate(),
                firstText(merchant.getStatus(), DEFAULT_STATUS),
                firstText(merchant.getSettlementStatus(), DEFAULT_SETTLEMENT_STATUS),
                merchant.getMd5Key(),
                firstText(merchant.getPlatformPublicKey(), PLATFORM_RSA.publicKey()),
                merchant.getRsa2PublicKey(),
                merchant.getRsa2PrivateKey(),
                firstText(merchant.getSignMode(), "MD5_RSA2"),
                merchant.getRoutingMode().name()
        );
        replaceMerchantChannels(merchant);
    }

    private void updateMerchant(DemoMerchant merchant) {
        jdbcTemplate.update("""
                UPDATE merchant
                SET name = ?, fee_rate = ?, status = ?, settlement_status = ?, md5_key = ?,
                    platform_public_key = ?, rsa2_public_key = ?, rsa2_private_key = ?,
                    sign_mode = ?, routing_mode = ?
                WHERE merchant_id = ?
                """,
                merchant.getName(),
                merchant.getFeeRate(),
                firstText(merchant.getStatus(), DEFAULT_STATUS),
                firstText(merchant.getSettlementStatus(), DEFAULT_SETTLEMENT_STATUS),
                merchant.getMd5Key(),
                firstText(merchant.getPlatformPublicKey(), PLATFORM_RSA.publicKey()),
                merchant.getRsa2PublicKey(),
                merchant.getRsa2PrivateKey(),
                firstText(merchant.getSignMode(), "MD5_RSA2"),
                merchant.getRoutingMode().name(),
                merchant.getMerchantId()
        );
        replaceMerchantChannels(merchant);
    }

    private void replaceMerchantChannels(DemoMerchant merchant) {
        jdbcTemplate.update("DELETE FROM merchant_channel WHERE merchant_id = ?", merchant.getMerchantId());
        for (String channelId : merchant.getChannelIds()) {
            jdbcTemplate.update(
                    "INSERT INTO merchant_channel (merchant_id, channel_id) VALUES (?, ?)",
                    merchant.getMerchantId(),
                    channelId
            );
        }
    }

    private void seed(DemoMerchant merchant) {
        merchants.put(merchant.getMerchantId(), merchant);
    }

    private DemoMerchant merchant(String merchantId) {
        if (databaseBacked()) {
            return readMerchant(merchantId);
        }
        DemoMerchant merchant = merchants.get(merchantId);
        if (merchant == null) {
            throw new IllegalArgumentException("鍟嗘埛涓嶅瓨鍦? " + merchantId);
        }
        return merchant;
    }

    private static DemoMerchant merchant(
            String merchantId,
            String name,
            BigDecimal feeRate,
            String status,
            BigDecimal todayAmount
    ) {
        return merchant(merchantId, name, feeRate, status, todayAmount, Set.of(), RoutingMode.ROUND_ROBIN);
    }

    private static DemoMerchant merchant(
            String merchantId,
            String name,
            BigDecimal feeRate,
            String status,
            BigDecimal todayAmount,
            Collection<String> channelIds,
            RoutingMode routingMode
    ) {
        RsaKeyPair rsa = rsaKeyPair();
        return new DemoMerchant(
                merchantId,
                name,
                feeRate,
                status,
                todayAmount,
                md5Key(),
                PLATFORM_RSA.publicKey(),
                rsa.publicKey(),
                rsa.privateKey(),
                DEFAULT_SETTLEMENT_STATUS,
                "MD5_RSA2",
                normalizeChannels(channelIds),
                routingMode
        );
    }

    private static Set<String> normalizeChannels(Collection<String> channelIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (channelIds != null) {
            channelIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        return normalized;
    }

    private static String md5Key() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("MD5");
        for (byte value : bytes) {
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private static RsaKeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RsaKeyPair(
                    pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                    pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("褰撳墠 JDK 涓嶆敮鎸?RSA 瀵嗛挜鐢熸垚", ex);
        }
    }

    private static String pem(String type, byte[] content) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(content);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private record RsaKeyPair(String publicKey, String privateKey) {
    }

    private static RoutingMode routingMode(String value) {
        if (!hasText(value)) {
            return RoutingMode.ROUND_ROBIN;
        }
        try {
            return RoutingMode.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            return RoutingMode.ROUND_ROBIN;
        }
    }

    private static String required(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
