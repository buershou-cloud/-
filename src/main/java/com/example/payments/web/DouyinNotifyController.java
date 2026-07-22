package com.example.payments.web;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.gateway.GatewayException;
import com.example.payments.gateway.douyin.DouyinPayClient;
import com.example.payments.gateway.douyin.DouyinSignatureSupport;
import com.example.payments.gateway.douyin.DouyinTradeState;
import com.example.payments.merchant.api.MerchantNotifyService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/douyin")
public class DouyinNotifyController {

    private static final Logger log = LoggerFactory.getLogger(DouyinNotifyController.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChannelRegistry channelRegistry;
    private final DouyinPayClient douyinPayClient;
    private final DemoOrderService orderService;
    private final MerchantNotifyService merchantNotifyService;
    private final ObjectMapper objectMapper;

    public DouyinNotifyController(
            ChannelRegistry channelRegistry,
            DouyinPayClient douyinPayClient,
            DemoOrderService orderService,
            MerchantNotifyService merchantNotifyService,
            ObjectMapper objectMapper
    ) {
        this.channelRegistry = channelRegistry;
        this.douyinPayClient = douyinPayClient;
        this.orderService = orderService;
        this.merchantNotifyService = merchantNotifyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/notify/{channelId}")
    public ResponseEntity<Void> notify(
            @PathVariable String channelId,
            @RequestHeader HttpHeaders headers,
            @RequestBody String rawBody
    ) {
        PaymentGatewayProperties.Channel channel = channelRegistry.find(channelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + channelId));
        if (!douyinPayClient.verifyNotification(
                channel,
                headers.getFirst("Douyinpay-Timestamp"),
                headers.getFirst("Douyinpay-Nonce"),
                headers.getFirst("Douyinpay-Signature"),
                headers.getFirst("Douyinpay-Serial"),
                rawBody
        )) {
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> event = json(rawBody, "DOUYIN_NOTIFY_INVALID", "Invalid Douyin Pay notification JSON");
        Map<String, Object> resource = map(event.get("resource"));
        if (!"AEAD-AES-256-GCM".equals(text(resource, "algorithm"))) {
            throw new GatewayException("DOUYIN_NOTIFY_ALGORITHM_INVALID", "Unsupported Douyin Pay notification algorithm");
        }
        String plainText = DouyinSignatureSupport.decrypt(
                text(resource, "associated_data"),
                required(resource, "nonce"),
                required(resource, "ciphertext"),
                channel.getDouyin().getEncryptKey()
        );
        Map<String, Object> decryptedPayload = json(
                plainText,
                "DOUYIN_NOTIFY_RESOURCE_INVALID",
                "Invalid encrypted Douyin Pay notification resource"
        );
        Map<String, Object> payload = bodyOrData(decryptedPayload);
        validateMerchant(channel, payload);

        String eventType = text(event, "event_type");
        String originalType = text(resource, "original_type");
        if ("REFUND.SUCCESS".equals(eventType) || "refund".equalsIgnoreCase(originalType)) {
            String refundStatus = firstText(text(payload, "refund_status"), text(payload, "status"), "SUCCESS");
            orderService.recordDouyinRefundNotify(
                    required(payload, "out_refund_no"),
                    refundStatus,
                    text(payload, "refund_id")
            );
            log.info("Processed Douyin refund notification channel={} outRefundNo={} status={}",
                    channelId, text(payload, "out_refund_no"), refundStatus);
            return ResponseEntity.ok().build();
        }

        if (isProfitSharingNotification(eventType, originalType, payload)) {
            // Profit-sharing is asynchronous. Its final state remains queryable with out_order_no,
            // and acknowledging the verified notification prevents unnecessary platform retries.
            return ResponseEntity.ok().build();
        }

        String outTradeNo = required(payload, "out_trade_no");
        String tradeState = required(payload, "trade_state");
        DemoOrderView order = orderService.recordPaymentResult(
                outTradeNo,
                text(payload, "transaction_id"),
                channelId,
                DouyinTradeState.toPaymentStatus(tradeState)
        );
        merchantNotifyService.notifyPayment(order, tradeState);
        log.info("Processed Douyin payment notification channel={} outTradeNo={} tradeState={} localStatus={}",
                channelId, outTradeNo, tradeState, order.status());
        return ResponseEntity.ok().build();
    }

    private void validateMerchant(PaymentGatewayProperties.Channel channel, Map<String, Object> payload) {
        String appId = text(payload, "appid");
        String mchId = text(payload, "mchid");
        if (appId != null && !appId.equals(channel.getDouyin().getAppId())) {
            throw new GatewayException("DOUYIN_NOTIFY_APP_ID_MISMATCH", "Douyin Pay notification appId mismatch");
        }
        if (mchId != null && !mchId.equals(channel.getDouyin().getMchId())) {
            throw new GatewayException("DOUYIN_NOTIFY_MCH_ID_MISMATCH", "Douyin Pay notification mchId mismatch");
        }
    }

    private Map<String, Object> json(String value, String code, String message) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            throw new GatewayException(code, message, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> result) {
            return (Map<String, Object>) result;
        }
        throw new GatewayException("DOUYIN_NOTIFY_RESOURCE_MISSING", "Douyin Pay notification resource is missing");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOrData(Map<String, Object> payload) {
        Object nested = payload == null ? null : payload.get("data");
        if (nested instanceof Map<?, ?> result) {
            return (Map<String, Object>) result;
        }
        return payload;
    }

    private static boolean isProfitSharingNotification(
            String eventType,
            String originalType,
            Map<String, Object> payload
    ) {
        String event = eventType == null ? "" : eventType.toUpperCase();
        String original = originalType == null ? "" : originalType.toUpperCase();
        return event.contains("PROFITSHARING")
                || original.contains("PROFITSHARING")
                || (payload.containsKey("out_order_no")
                    && payload.containsKey("state")
                    && !payload.containsKey("trade_state"));
    }

    private static String required(Map<String, Object> data, String key) {
        String value = text(data, key);
        if (value == null || value.isBlank()) {
            throw new GatewayException("DOUYIN_NOTIFY_FIELD_MISSING", "Douyin Pay notification is missing " + key);
        }
        return value;
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value == null ? null : value.toString();
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
