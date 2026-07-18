package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.alipay.AlipayOpenApiClient;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.gateway.douyin.DouyinSignatureSupport;
import com.example.payments.payout.MerchantPayoutService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payouts/notify")
public class MerchantPayoutNotifyController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChannelRegistry channelRegistry;
    private final MerchantPayoutService payoutService;
    private final AlipayOpenApiClient alipayClient;
    private final DouyinPayClient douyinClient;
    private final ObjectMapper objectMapper;

    public MerchantPayoutNotifyController(
            ChannelRegistry channelRegistry,
            MerchantPayoutService payoutService,
            AlipayOpenApiClient alipayClient,
            DouyinPayClient douyinClient,
            ObjectMapper objectMapper
    ) {
        this.channelRegistry = channelRegistry;
        this.payoutService = payoutService;
        this.alipayClient = alipayClient;
        this.douyinClient = douyinClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/alipay/{channelId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> alipayForm(
            @PathVariable String channelId,
            @RequestParam MultiValueMap<String, String> form
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        form.forEach((key, values) -> params.put(
                key,
                values == null || values.isEmpty() ? null : values.getFirst()
        ));
        return recordAlipay(channelId, params);
    }

    @PostMapping(path = "/alipay/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> alipayJson(@PathVariable String channelId, @RequestBody String rawBody) {
        Map<String, Object> message = json(rawBody, "ALIPAY_PAYOUT_NOTIFY_INVALID");
        Map<String, String> params = new LinkedHashMap<>();
        message.forEach((key, value) -> {
            if (value != null && !(value instanceof Map<?, ?>)) {
                params.put(key, String.valueOf(value));
            }
        });
        return recordAlipay(channelId, params);
    }

    @PostMapping("/douyin/{channelId}")
    public ResponseEntity<Void> douyin(
            @PathVariable String channelId,
            @RequestHeader HttpHeaders headers,
            @RequestBody String rawBody
    ) {
        PaymentGatewayProperties.Channel channel = channel(channelId);
        if (!douyinClient.verifyNotification(
                channel,
                headers.getFirst("Douyinpay-Timestamp"),
                headers.getFirst("Douyinpay-Nonce"),
                headers.getFirst("Douyinpay-Signature"),
                headers.getFirst("Douyinpay-Serial"),
                rawBody
        )) {
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> event = json(rawBody, "DOUYIN_PAYOUT_NOTIFY_INVALID");
        Map<String, Object> resource = map(event.get("resource"));
        String algorithm = text(resource, "algorithm");
        if (!"AEAD-AES-256-GCM".equals(algorithm) && !"AEAD_AES_256_GCM".equals(algorithm)) {
            throw new GatewayException("DOUYIN_PAYOUT_NOTIFY_ALGORITHM_INVALID", "Unsupported Douyin Pay notification algorithm");
        }
        String plainText = DouyinSignatureSupport.decrypt(
                text(resource, "associated_data"),
                required(resource, "nonce"),
                required(resource, "ciphertext"),
                channel.getDouyin().getEncryptKey()
        );
        Map<String, Object> payload = json(plainText, "DOUYIN_PAYOUT_NOTIFY_RESOURCE_INVALID");
        String mchId = firstText(text(payload, "mch_id"), text(payload, "mchid"));
        if (mchId != null && !mchId.equals(channel.getDouyin().getMchId())) {
            throw new GatewayException("DOUYIN_PAYOUT_NOTIFY_MERCHANT_MISMATCH", "Douyin payout notification merchant mismatch");
        }
        payoutService.recordDouyinNotification(
                channelId,
                required(payload, "out_bill_no"),
                text(payload, "transfer_bill_no"),
                requiredFirst(payload, "state", "transfer_state"),
                fenAmount(payload.get("transfer_amount")),
                text(payload, "fail_reason"),
                payload
        );
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<String> recordAlipay(String channelId, Map<String, String> params) {
        PaymentGatewayProperties.Channel channel = channel(channelId);
        if (!alipayClient.verifyNotify(channel, params)) {
            return ResponseEntity.badRequest().body("failure");
        }
        Map<String, Object> payload = alipayPayload(params);
        String appId = firstText(text(payload, "app_id"), params.get("app_id"));
        if (appId != null && !appId.equals(channel.getAlipay().getAppId())) {
            return ResponseEntity.badRequest().body("failure");
        }
        payoutService.recordAlipayNotification(
                channelId,
                required(payload, "out_biz_no"),
                text(payload, "order_id"),
                text(payload, "pay_fund_order_id"),
                required(payload, "status"),
                decimalAmount(firstText(text(payload, "trans_amount"), text(payload, "amount"))),
                payload
        );
        return ResponseEntity.ok("success");
    }

    private Map<String, Object> alipayPayload(Map<String, String> params) {
        String bizContent = params.get("biz_content");
        Map<String, Object> payload = bizContent == null || bizContent.isBlank()
                ? new LinkedHashMap<>()
                : json(bizContent, "ALIPAY_PAYOUT_NOTIFY_CONTENT_INVALID");
        params.forEach(payload::putIfAbsent);
        return payload;
    }

    private PaymentGatewayProperties.Channel channel(String channelId) {
        return channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
    }

    private Map<String, Object> json(String value, String code) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            throw new GatewayException(code, "Invalid payout notification JSON", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> result) {
            return (Map<String, Object>) result;
        }
        throw new GatewayException("PAYOUT_NOTIFY_RESOURCE_MISSING", "Payout notification resource is missing");
    }

    private static BigDecimal fenAmount(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value)).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal decimalAmount(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static String required(Map<String, Object> data, String key) {
        String value = text(data, key);
        if (value == null || value.isBlank()) {
            throw new GatewayException("PAYOUT_NOTIFY_FIELD_MISSING", "Payout notification is missing " + key);
        }
        return value;
    }

    private static String requiredFirst(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            String value = text(data, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new GatewayException(
                "PAYOUT_NOTIFY_FIELD_MISSING",
                "Payout notification is missing " + String.join(" or ", keys)
        );
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }
}
