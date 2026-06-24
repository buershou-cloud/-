package com.example.payments.order;

import java.math.BigDecimal;

public class DemoOrder {

    private String outTradeNo;
    private String tradeNo;
    private String channelId;
    private String merchantId;
    private String merchantName;
    private String productName;
    private String subject;
    private BigDecimal amount;
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    private BigDecimal preauthUnfrozenAmount = BigDecimal.ZERO;
    private DemoOrderStatus status;
    private String createdAt;
    private boolean preAuthorization;
    private boolean supplemented;
    private boolean profitShared;

    public DemoOrder(
            String outTradeNo,
            String tradeNo,
            String channelId,
            String merchantId,
            String merchantName,
            String productName,
            String subject,
            BigDecimal amount,
            DemoOrderStatus status,
            String createdAt,
            boolean preAuthorization
    ) {
        this.outTradeNo = outTradeNo;
        this.tradeNo = tradeNo;
        this.channelId = channelId;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.productName = productName;
        this.subject = subject;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.preAuthorization = preAuthorization;
    }

    public String getOutTradeNo() {
        return outTradeNo;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public void setTradeNo(String tradeNo) {
        this.tradeNo = tradeNo;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
    }

    public BigDecimal getPreauthUnfrozenAmount() {
        return preauthUnfrozenAmount == null ? BigDecimal.ZERO : preauthUnfrozenAmount;
    }

    public void setPreauthUnfrozenAmount(BigDecimal preauthUnfrozenAmount) {
        this.preauthUnfrozenAmount = preauthUnfrozenAmount == null ? BigDecimal.ZERO : preauthUnfrozenAmount;
    }

    public DemoOrderStatus getStatus() {
        return status;
    }

    public void setStatus(DemoOrderStatus status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public boolean isPreAuthorization() {
        return preAuthorization;
    }

    public void setPreAuthorization(boolean preAuthorization) {
        this.preAuthorization = preAuthorization;
    }

    public boolean isSupplemented() {
        return supplemented;
    }

    public void setSupplemented(boolean supplemented) {
        this.supplemented = supplemented;
    }

    public boolean isProfitShared() {
        return profitShared;
    }

    public void setProfitShared(boolean profitShared) {
        this.profitShared = profitShared;
    }
}
