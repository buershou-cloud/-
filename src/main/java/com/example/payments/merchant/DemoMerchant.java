package com.example.payments.merchant;

import com.example.payments.domain.RoutingMode;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

public class DemoMerchant {

    private final String merchantId;
    private String name;
    private BigDecimal feeRate;
    private String status;
    private BigDecimal todayAmount;
    private String md5Key;
    private final String platformPublicKey;
    private String rsa2PublicKey;
    private String rsa2PrivateKey;
    private String settlementStatus;
    private String signMode;
    private Set<String> channelIds;
    private RoutingMode routingMode;

    public DemoMerchant(
            String merchantId,
            String name,
            BigDecimal feeRate,
            String status,
            BigDecimal todayAmount,
            String md5Key,
            String platformPublicKey,
            String rsa2PublicKey,
            String rsa2PrivateKey,
            String settlementStatus,
            String signMode,
            Set<String> channelIds,
            RoutingMode routingMode
    ) {
        this.merchantId = merchantId;
        this.name = name;
        this.feeRate = feeRate;
        this.status = status;
        this.todayAmount = todayAmount;
        this.md5Key = md5Key;
        this.platformPublicKey = platformPublicKey;
        this.rsa2PublicKey = rsa2PublicKey;
        this.rsa2PrivateKey = rsa2PrivateKey;
        this.settlementStatus = settlementStatus;
        this.signMode = signMode;
        this.channelIds = normalizeChannels(channelIds);
        this.routingMode = routingMode == null ? RoutingMode.ROUND_ROBIN : routingMode;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTodayAmount() {
        return todayAmount;
    }

    public void setTodayAmount(BigDecimal todayAmount) {
        this.todayAmount = todayAmount;
    }

    public String getMd5Key() {
        return md5Key;
    }

    public void setMd5Key(String md5Key) {
        this.md5Key = md5Key;
    }

    public String getPlatformPublicKey() {
        return platformPublicKey;
    }

    public String getRsa2PublicKey() {
        return rsa2PublicKey;
    }

    public void setRsa2PublicKey(String rsa2PublicKey) {
        this.rsa2PublicKey = rsa2PublicKey;
    }

    public String getRsa2PrivateKey() {
        return rsa2PrivateKey;
    }

    public void setRsa2PrivateKey(String rsa2PrivateKey) {
        this.rsa2PrivateKey = rsa2PrivateKey;
    }

    public String getSettlementStatus() {
        return settlementStatus;
    }

    public void setSettlementStatus(String settlementStatus) {
        this.settlementStatus = settlementStatus;
    }

    public String getSignMode() {
        return signMode;
    }

    public void setSignMode(String signMode) {
        this.signMode = signMode;
    }

    public Set<String> getChannelIds() {
        return Set.copyOf(channelIds);
    }

    public void setChannelIds(Set<String> channelIds) {
        this.channelIds = normalizeChannels(channelIds);
    }

    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    public void setRoutingMode(RoutingMode routingMode) {
        this.routingMode = routingMode == null ? RoutingMode.ROUND_ROBIN : routingMode;
    }

    private static Set<String> normalizeChannels(Set<String> channelIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (channelIds != null) {
            channelIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        return normalized;
    }
}
