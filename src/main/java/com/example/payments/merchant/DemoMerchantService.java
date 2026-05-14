package com.example.payments.merchant;

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
import com.example.payments.domain.RoutingMode;

@Service
public class DemoMerchantService {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RsaKeyPair PLATFORM_RSA = rsaKeyPair();

    private final Map<String, DemoMerchant> merchants = new LinkedHashMap<>();

    public DemoMerchantService() {
        seed(merchant("M10001", "示例商户A", new BigDecimal("0.60"), "正常", new BigDecimal("6880.00")));
        seed(merchant("M10002", "示例商户B", new BigDecimal("0.55"), "正常", new BigDecimal("3210.00")));
        seed(merchant("M10003", "示例商户C", new BigDecimal("0.65"), "待审核", BigDecimal.ZERO));
    }

    public synchronized List<DemoMerchantView> list() {
        return merchants.values().stream()
                .map(DemoMerchantView::from)
                .toList();
    }

    public synchronized DemoMerchantView create(DemoMerchantCreateRequest request) {
        String merchantId = hasText(request.merchantId())
                ? request.merchantId().trim()
                : "M" + LocalDateTime.now().format(ID_TIME);
        if (merchants.containsKey(merchantId)) {
            throw new IllegalArgumentException("商户号已存在: " + merchantId);
        }
        DemoMerchant merchant = merchant(
                merchantId,
                required(request.name(), "商户名称不能为空"),
                request.feeRate() == null ? new BigDecimal("0.60") : request.feeRate(),
                hasText(request.status()) ? request.status().trim() : "正常",
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
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView detail(String merchantId) {
        return DemoMerchantView.from(merchant(merchantId));
    }

    public synchronized DemoMerchantView delete(String merchantId) {
        DemoMerchant removed = merchants.remove(merchantId);
        if (removed == null) {
            throw new IllegalArgumentException("商户不存在: " + merchantId);
        }
        return DemoMerchantView.from(removed);
    }

    public synchronized DemoMerchantView settle(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setSettlementStatus("已结算");
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView resetMd5(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setMd5Key(md5Key());
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView generateRsa2(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        RsaKeyPair rsa = rsaKeyPair();
        merchant.setRsa2PublicKey(rsa.publicKey());
        merchant.setRsa2PrivateKey(rsa.privateKey());
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView updateSignMode(String merchantId, String signMode) {
        DemoMerchant merchant = merchant(merchantId);
        merchant.setSignMode(hasText(signMode) ? signMode.trim() : "MD5_RSA2");
        return DemoMerchantView.from(merchant);
    }

    public synchronized DemoMerchantView login(String merchantId, String md5Key, boolean demoLogin) {
        DemoMerchant merchant = merchant(merchantId);
        if (!demoLogin && !merchant.getMd5Key().equals(md5Key)) {
            throw new IllegalArgumentException("商户号或 MD5 密钥不正确");
        }
        return DemoMerchantView.from(merchant);
    }

    public synchronized MerchantRouting routing(String merchantId) {
        DemoMerchant merchant = merchant(merchantId);
        return new MerchantRouting(merchant.getChannelIds(), merchant.getRoutingMode());
    }

    private void seed(DemoMerchant merchant) {
        merchants.put(merchant.getMerchantId(), merchant);
    }

    private DemoMerchant merchant(String merchantId) {
        DemoMerchant merchant = merchants.get(merchantId);
        if (merchant == null) {
            throw new IllegalArgumentException("商户不存在: " + merchantId);
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
                "待结算",
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
            throw new IllegalStateException("当前 JDK 不支持 RSA 密钥生成", ex);
        }
    }

    private static String pem(String type, byte[] content) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(content);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private record RsaKeyPair(String publicKey, String privateKey) {
    }

    private static String required(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
