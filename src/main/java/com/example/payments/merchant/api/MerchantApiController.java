package com.example.payments.merchant.api;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.channel.ChannelSelector;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.*;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/merchant-api")
public class MerchantApiController {
    private static final Logger log = LoggerFactory.getLogger(MerchantApiController.class);
    private final PaymentGatewayService paymentGatewayService;
    private final DemoMerchantService merchantService;
    private final DemoOrderService orderService;
    private final MerchantSignatureService signatureService;
    private final ChannelRegistry channelRegistry;
    private final ChannelSelector channelSelector;
    private final MerchantNotifyService notifyService;

    public MerchantApiController(PaymentGatewayService paymentGatewayService, DemoMerchantService merchantService,
                                 DemoOrderService orderService, MerchantSignatureService signatureService,
                                 ChannelRegistry channelRegistry, ChannelSelector channelSelector,
                                 MerchantNotifyService notifyService) {
        this.paymentGatewayService = paymentGatewayService;
        this.merchantService = merchantService;
        this.orderService = orderService;
        this.signatureService = signatureService;
        this.channelRegistry = channelRegistry;
        this.channelSelector = channelSelector;
        this.notifyService = notifyService;
    }

    @PostMapping("/check")
    public MerchantApiResponse<?> check(@Valid @RequestBody MerchantApiCheckRequest request) {
        validateResponseMode();
        DemoMerchantView merchant = signatureService.verify(request);
        Set<String> bound = merchantService.routing(merchant.merchantId()).channelIds();
        List<String> channelIds = bound == null ? List.of() : bound.stream().sorted().toList();
        List<PaymentGatewayProperties.Channel> enabled = channelRegistry.all().stream()
                .filter(channel -> channelIds.contains(channel.getId()))
                .filter(channelRegistry::isEnabled).filter(PaymentGatewayProperties.Channel::isDailyEnabled)
                .filter(channel -> !supportedProducts(channel).isEmpty()).toList();
        List<PaymentGatewayProperties.Channel> available = enabled.stream().filter(MerchantApiController::hasGatewayCallback).toList();
        Map<String, Object> readiness = Map.of(
                "availableChannelCount", available.size(),
                "availableChannelIds", available.stream().map(PaymentGatewayProperties.Channel::getId).toList(),
                "configurationWarnings", enabled.stream().filter(channel -> !hasGatewayCallback(channel))
                        .map(channel -> Map.of("channelId", channel.getId(), "code", "GATEWAY_CALLBACK_NOT_CONFIGURED")).toList(),
                "products", available.stream().flatMap(channel -> supportedProducts(channel).stream()).distinct().sorted().toList());
        return signatureService.successCanonical(merchant, request.signType(), Map.of(
                "merchantId", merchant.merchantId(), "serverTime", Instant.now().toString(),
                "signMode", merchant.signMode(), "channelIds", channelIds, "readiness", readiness));
    }

    @PostMapping("/pay")
    public MerchantApiResponse<?> pay(@Valid @RequestBody MerchantApiPayRequest request) {
        validateResponseMode();
        DemoMerchantView merchant = signatureService.verify(request);
        request.validateMerchantFields();
        List<String> channelIds = safeChannelIds(merchant, request.channelIds());
        List<String> compatible = channelIds.stream().filter(id -> channelRegistry.find(id)
                .map(channel -> supportedProducts(channel).contains(request.product().name())).orElse(false)).toList();
        if (compatible.isEmpty()) {
            throw new MerchantApiException("NO_AVAILABLE_CHANNEL", "No bound channel supports the requested product");
        }
        PaymentGatewayProperties.Channel channel;
        try {
            channel = channelSelector.select(request.product(), compatible, 1, request.totalAmount(),
                    request.routingMode() == null ? merchant.routingMode() : request.routingMode()).getFirst();
        } catch (IllegalStateException ex) {
            throw new MerchantApiException("NO_AVAILABLE_CHANNEL", "No enabled bound channel matches the product and amount");
        }
        if (!hasGatewayCallback(channel)) {
            throw new MerchantApiException("CHANNEL_NOT_READY", "The selected channel requires a gateway payment callback URL configured by the administrator");
        }
        PayCreateRequest payment = request.toPayCreateRequest(merchant, List.of(channel.getId()));
        // Reserve before any upstream call, including calls that time out.
        orderService.reserveMerchantPayment(merchant.merchantId(), merchant.name(), channel.getId(), payment);
        GatewayResponse result = paymentGatewayService.pay(payment);
        return response(merchant, request.signType(), result);
    }

    @PostMapping("/query")
    public MerchantApiResponse<?> query(@Valid @RequestBody MerchantApiQueryRequest request) {
        validateResponseMode();
        DemoMerchantView merchant = signatureService.verify(request);
        DemoOrderView order = orderService.resolveMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        List<String> channelIds = orderChannel(merchant, order, request.channelIds());
        GatewayResponse result = paymentGatewayService.query(new PaymentQueryRequest(order.outTradeNo(), order.tradeNo(),
                null, channelIds, Map.of("merchantId", merchant.merchantId())));
        if (result.status() == PaymentStatus.SUCCESS) {
            try {
                notifyService.notifyPayment(orderService.view(order.outTradeNo()), "TRADE_SUCCESS");
            } catch (RuntimeException ex) {
                log.warn("Merchant callback remains pending after successful query for order {}", order.outTradeNo());
            }
        }
        return response(merchant, request.signType(), result);
    }

    @PostMapping("/cancel")
    public MerchantApiResponse<?> cancel(@Valid @RequestBody MerchantApiCancelRequest request) {
        validateResponseMode();
        DemoMerchantView merchant = signatureService.verify(request);
        DemoOrderView order = orderService.resolveMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        GatewayResponse result = paymentGatewayService.cancel(new PaymentCancelRequest(order.outTradeNo(), order.tradeNo(),
                null, orderChannel(merchant, order, request.channelIds()), Map.of("merchantId", merchant.merchantId())));
        return response(merchant, request.signType(), result);
    }

    @PostMapping("/refund")
    public MerchantApiResponse<?> refund(@Valid @RequestBody MerchantApiRefundRequest request) {
        validateResponseMode();
        DemoMerchantView merchant = signatureService.verify(request);
        DemoOrderView order = orderService.resolveMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        RefundCreateRequest refund = new RefundCreateRequest(order.outTradeNo(), order.tradeNo(), request.refundAmount(),
                request.outRequestNo(), request.refundReason(), null, orderChannel(merchant, order, request.channelIds()),
                Map.of("merchantId", merchant.merchantId()));
        orderService.reserveMerchantRefund(merchant.merchantId(), refund);
        GatewayResponse result = paymentGatewayService.refund(refund);
        // Gateway FAILED also represents transport timeouts. Keep the reservation until reconciled.
        return response(merchant, request.signType(), result);
    }

    private MerchantApiResponse<?> response(DemoMerchantView merchant, String signType, Object data) {
        return canonicalResponse() ? signatureService.successCanonical(merchant, signType, data)
                : signatureService.success(merchant, signType, data);
    }

    private static String responseMode() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getHeader("X-Merchant-Response-Signature");
        }
        return null;
    }

    private static boolean canonicalResponse() {
        return "canonical-v1".equals(responseMode());
    }

    private static void validateResponseMode() {
        String mode = responseMode();
        if (mode != null && !mode.isBlank() && !"canonical-v1".equals(mode)) {
            throw new MerchantApiException("UNSUPPORTED_RESPONSE_SIGNATURE", "Supported response signature mode: canonical-v1");
        }
    }

    private List<String> orderChannel(DemoMerchantView merchant, DemoOrderView order, List<String> requested) {
        List<String> allowed = safeChannelIds(merchant, requested);
        if (order.channelId() == null || !allowed.contains(order.channelId())) {
            throw new MerchantApiException("CHANNEL_FORBIDDEN", "The original order channel is not bound or was excluded by channelIds");
        }
        return List.of(order.channelId());
    }

    private List<String> safeChannelIds(DemoMerchantView merchant, List<String> requested) {
        Set<String> bound = merchantService.routing(merchant.merchantId()).channelIds();
        if (bound == null || bound.isEmpty()) {
            throw new MerchantApiException("CHANNEL_FORBIDDEN", "No payment channel is bound to this merchant");
        }
        if (requested == null || requested.isEmpty()) {
            return bound.stream().sorted().toList();
        }
        if (requested.stream().anyMatch(id -> id == null || !bound.contains(id))) {
            throw new MerchantApiException("CHANNEL_FORBIDDEN", "Every requested channel must be bound to this merchant");
        }
        return requested.stream().distinct().toList();
    }

    private static List<String> supportedProducts(PaymentGatewayProperties.Channel channel) {
        return Arrays.stream(PaymentProduct.values()).filter(product -> switch (channel.getProvider()) {
                    case "DOUYIN" -> product.douyinPaymentProduct();
                    case "ALIPAY", "ALIPAY_DIRECT" -> !product.douyinPaymentProduct();
                    default -> false;
                }).filter(product -> channel.getProducts().isEmpty() || channel.getProducts().contains(product))
                .map(Enum::name).toList();
    }

    private static boolean hasGatewayCallback(PaymentGatewayProperties.Channel channel) {
        String url = "DOUYIN".equals(channel.getProvider()) ? channel.getDouyin().getNotifyUrl() : channel.getAlipay().getNotifyUrl();
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
