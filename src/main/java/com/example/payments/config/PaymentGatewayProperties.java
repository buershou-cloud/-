package com.example.payments.config;

import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "payment")
public class PaymentGatewayProperties {

    private Routing routing = new Routing();
    private Operations operations = new Operations();
    private List<Channel> channels = new ArrayList<>();

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public Operations getOperations() {
        return operations;
    }

    public void setOperations(Operations operations) {
        this.operations = operations;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public void setChannels(List<Channel> channels) {
        this.channels = channels;
    }

    public static class Routing {
        private int maxAttempts = 3;
        private boolean failover = true;
        private RoutingMode mode = RoutingMode.ROUND_ROBIN;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public boolean isFailover() {
            return failover;
        }

        public void setFailover(boolean failover) {
            this.failover = failover;
        }

        public RoutingMode getMode() {
            return mode;
        }

        public void setMode(RoutingMode mode) {
            this.mode = mode;
        }
    }

    public static class Operations {
        private String complaintListMethod = "alipay.security.risk.complaint.info.batchquery";
        private String complaintDetailMethod = "alipay.security.risk.complaint.info.query";
        private String onboardingMethod = "ant.merchant.expand.indirect.zft.simplecreate";
        private String onboardingQueryMethod = "ant.merchant.expand.indirect.zftorder.query";
        private String onboardingCancelMethod = "ant.merchant.expand.indirect.zft.delete";

        public String getComplaintListMethod() {
            return complaintListMethod;
        }

        public void setComplaintListMethod(String complaintListMethod) {
            this.complaintListMethod = complaintListMethod;
        }

        public String getComplaintDetailMethod() {
            return complaintDetailMethod;
        }

        public void setComplaintDetailMethod(String complaintDetailMethod) {
            this.complaintDetailMethod = complaintDetailMethod;
        }

        public String getOnboardingMethod() {
            return onboardingMethod;
        }

        public void setOnboardingMethod(String onboardingMethod) {
            this.onboardingMethod = onboardingMethod;
        }

        public String getOnboardingQueryMethod() {
            return onboardingQueryMethod;
        }

        public void setOnboardingQueryMethod(String onboardingQueryMethod) {
            this.onboardingQueryMethod = onboardingQueryMethod;
        }

        public String getOnboardingCancelMethod() {
            return onboardingCancelMethod;
        }

        public void setOnboardingCancelMethod(String onboardingCancelMethod) {
            this.onboardingCancelMethod = onboardingCancelMethod;
        }
    }

    public static class Channel {
        private String id;
        private String provider = "ALIPAY";
        private boolean enabled = true;
        private boolean dailyEnabled = true;
        private int priority = 100;
        private int weight = 1;
        private BigDecimal payMin;
        private BigDecimal payMax;
        private Set<PaymentProduct> products = new LinkedHashSet<>();
        private Alipay alipay = new Alipay();
        private Douyin douyin = new Douyin();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDailyEnabled() {
            return dailyEnabled;
        }

        public void setDailyEnabled(boolean dailyEnabled) {
            this.dailyEnabled = dailyEnabled;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public BigDecimal getPayMin() {
            return payMin;
        }

        public void setPayMin(BigDecimal payMin) {
            this.payMin = payMin;
        }

        public BigDecimal getPayMax() {
            return payMax;
        }

        public void setPayMax(BigDecimal payMax) {
            this.payMax = payMax;
        }

        public Set<PaymentProduct> getProducts() {
            return products;
        }

        public void setProducts(Set<PaymentProduct> products) {
            this.products = products;
        }

        public Alipay getAlipay() {
            return alipay;
        }

        public void setAlipay(Alipay alipay) {
            this.alipay = alipay;
        }

        public Douyin getDouyin() {
            return douyin;
        }

        public void setDouyin(Douyin douyin) {
            this.douyin = douyin;
        }
    }

    public static class Alipay {
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";
        private String appId;
        private String merchantPrivateKey;
        private String alipayPublicKey;
        private String credentialMode = "PUBLIC_KEY";
        private String appCertSn;
        private String alipayCertSn;
        private String alipayRootCertSn;
        private String appCertContent;
        private String alipayCertContent;
        private String alipayRootCertContent;
        private String appAuthToken;
        private String subMerchantId;
        private String signType = "RSA2";
        private String charset = "UTF-8";
        private String notifyUrl;
        private String returnUrl;

        public String getGatewayUrl() {
            return gatewayUrl;
        }

        public void setGatewayUrl(String gatewayUrl) {
            this.gatewayUrl = gatewayUrl;
        }

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getMerchantPrivateKey() {
            return merchantPrivateKey;
        }

        public void setMerchantPrivateKey(String merchantPrivateKey) {
            this.merchantPrivateKey = merchantPrivateKey;
        }

        public String getAlipayPublicKey() {
            return alipayPublicKey;
        }

        public void setAlipayPublicKey(String alipayPublicKey) {
            this.alipayPublicKey = alipayPublicKey;
        }

        public String getCredentialMode() {
            return credentialMode;
        }

        public void setCredentialMode(String credentialMode) {
            this.credentialMode = credentialMode;
        }

        public String getAppCertSn() {
            return appCertSn;
        }

        public void setAppCertSn(String appCertSn) {
            this.appCertSn = appCertSn;
        }

        public String getAlipayCertSn() {
            return alipayCertSn;
        }

        public void setAlipayCertSn(String alipayCertSn) {
            this.alipayCertSn = alipayCertSn;
        }

        public String getAlipayRootCertSn() {
            return alipayRootCertSn;
        }

        public void setAlipayRootCertSn(String alipayRootCertSn) {
            this.alipayRootCertSn = alipayRootCertSn;
        }

        public String getAppCertContent() {
            return appCertContent;
        }

        public void setAppCertContent(String appCertContent) {
            this.appCertContent = appCertContent;
        }

        public String getAlipayCertContent() {
            return alipayCertContent;
        }

        public void setAlipayCertContent(String alipayCertContent) {
            this.alipayCertContent = alipayCertContent;
        }

        public String getAlipayRootCertContent() {
            return alipayRootCertContent;
        }

        public void setAlipayRootCertContent(String alipayRootCertContent) {
            this.alipayRootCertContent = alipayRootCertContent;
        }

        public String getAppAuthToken() {
            return appAuthToken;
        }

        public void setAppAuthToken(String appAuthToken) {
            this.appAuthToken = appAuthToken;
        }

        public String getSubMerchantId() {
            return subMerchantId;
        }

        public void setSubMerchantId(String subMerchantId) {
            this.subMerchantId = subMerchantId;
        }

        public String getSignType() {
            return signType;
        }

        public void setSignType(String signType) {
            this.signType = signType;
        }

        public String getCharset() {
            return charset;
        }

        public void setCharset(String charset) {
            this.charset = charset;
        }

        public String getNotifyUrl() {
            return notifyUrl;
        }

        public void setNotifyUrl(String notifyUrl) {
            this.notifyUrl = notifyUrl;
        }

        public String getReturnUrl() {
            return returnUrl;
        }

        public void setReturnUrl(String returnUrl) {
            this.returnUrl = returnUrl;
        }
    }

    public static class Douyin {
        private String gatewayUrl = "https://api.douyinpay.com";
        private String appId;
        private String mchId;
        private String merchantSerialNo;
        private String merchantCertificate;
        private String merchantPrivateKey;
        private String platformCertificate;
        private String encryptKey;
        private String notifyUrl;
        private String returnUrl;
        private String transferRequestIp;
        private String h5AppName = "支付平台";

        public String getGatewayUrl() { return gatewayUrl; }
        public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        public String getMchId() { return mchId; }
        public void setMchId(String mchId) { this.mchId = mchId; }
        public String getMerchantSerialNo() { return merchantSerialNo; }
        public void setMerchantSerialNo(String merchantSerialNo) { this.merchantSerialNo = merchantSerialNo; }
        public String getMerchantCertificate() { return merchantCertificate; }
        public void setMerchantCertificate(String merchantCertificate) { this.merchantCertificate = merchantCertificate; }
        public String getMerchantPrivateKey() { return merchantPrivateKey; }
        public void setMerchantPrivateKey(String merchantPrivateKey) { this.merchantPrivateKey = merchantPrivateKey; }
        public String getPlatformCertificate() { return platformCertificate; }
        public void setPlatformCertificate(String platformCertificate) { this.platformCertificate = platformCertificate; }
        public String getEncryptKey() { return encryptKey; }
        public void setEncryptKey(String encryptKey) { this.encryptKey = encryptKey; }
        public String getNotifyUrl() { return notifyUrl; }
        public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
        public String getReturnUrl() { return returnUrl; }
        public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
        public String getH5AppName() { return h5AppName; }
        public void setH5AppName(String h5AppName) { this.h5AppName = h5AppName; }
        public String getTransferRequestIp() { return transferRequestIp; }
        public void setTransferRequestIp(String transferRequestIp) { this.transferRequestIp = transferRequestIp; }
    }
}
