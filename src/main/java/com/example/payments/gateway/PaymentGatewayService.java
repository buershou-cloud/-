package com.example.payments.gateway;

import com.example.payments.channel.ChannelSelector;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ChannelAttempt;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingBatchItem;
import com.example.payments.domain.ProfitSharingBatchRequest;
import com.example.payments.domain.ProfitSharingBatchResult;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.domain.RoutingMode;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.MerchantRouting;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PaymentGatewayService {

    private final PaymentGatewayProperties properties;
    private final ChannelSelector channelSelector;
    private final Map<String, PaymentProvider> providers;
    private final DemoOrderService orderService;
    private final DemoMerchantService merchantService;

    public PaymentGatewayService(
            PaymentGatewayProperties properties,
            ChannelSelector channelSelector,
            List<PaymentProvider> providers,
            DemoOrderService orderService,
            DemoMerchantService merchantService
    ) {
        this.properties = properties;
        this.channelSelector = channelSelector;
        this.providers = providers.stream().collect(Collectors.toMap(PaymentProvider::providerCode, Function.identity()));
        this.orderService = orderService;
        this.merchantService = merchantService;
    }

    public GatewayResponse pay(PayCreateRequest request) {
        MerchantRouting merchantRouting = merchantRouting(request);
        GatewayResponse response = execute(
                request.product(),
                channelIds(request.channelIds(), merchantRouting),
                request.totalAmount(),
                routingMode(request.routingMode(), merchantRouting),
                channel -> provider(channel).pay(channel, request)
        );
        recordOrder(request, response);
        return response;
    }

    public GatewayResponse query(PaymentQueryRequest request) {
        return execute(null, request.channelIds(), null, null, channel -> provider(channel).query(channel, request));
    }

    public GatewayResponse refund(RefundCreateRequest request) {
        return execute(null, request.channelIds(), request.refundAmount(), null, channel -> provider(channel).refund(channel, request));
    }

    public GatewayResponse profitSharing(ProfitSharingRequest request) {
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> provider(channel).profitSharing(channel, request));
        if (response.status() != PaymentStatus.FAILED && hasText(request.outTradeNo())) {
            orderService.markProfitShared(request.outTradeNo());
        }
        return response;
    }

    public ProfitSharingBatchResult profitSharingByChannel(ProfitSharingBatchRequest request) {
        List<DemoOrderView> orders = orderService.shareableByChannel(request.channelId(), request.includeProfitShared());
        List<ProfitSharingBatchItem> items = new ArrayList<>();
        int success = 0;
        int failed = 0;

        for (DemoOrderView order : orders) {
            String outRequestNo = outRequestNo(request, order);
            ProfitSharingRequest sharingRequest = new ProfitSharingRequest(
                    order.outTradeNo(),
                    order.tradeNo(),
                    outRequestNo,
                    List.of(royaltyParameter(request, order)),
                    request.operatorId(),
                    request.appAuthToken(),
                    List.of(request.channelId()),
                    request.extra()
            );
            try {
                GatewayResponse response = profitSharing(sharingRequest);
                if (response.status() == PaymentStatus.FAILED) {
                    failed++;
                } else {
                    success++;
                }
                items.add(new ProfitSharingBatchItem(
                        order.outTradeNo(),
                        order.tradeNo(),
                        outRequestNo,
                        response.status(),
                        response.code(),
                        response.message()
                ));
            } catch (RuntimeException ex) {
                failed++;
                items.add(new ProfitSharingBatchItem(
                        order.outTradeNo(),
                        order.tradeNo(),
                        outRequestNo,
                        PaymentStatus.FAILED,
                        "PROFIT_SHARING_EXCEPTION",
                        ex.getMessage()
                ));
            }
        }

        return new ProfitSharingBatchResult(
                request.channelId(),
                orders.size(),
                success,
                failed,
                "通道 " + request.channelId() + " 已处理 " + orders.size() + " 笔订单，成功 " + success + " 笔，失败 " + failed + " 笔",
                List.copyOf(items)
        );
    }

    public GatewayResponse queryComplaints(ComplaintQueryRequest request) {
        return execute(null, request.channelIds(), null, null, channel -> provider(channel).queryComplaints(channel, request));
    }

    public GatewayResponse queryComplaintsAllChannels(ComplaintQueryRequest request) {
        List<PaymentGatewayProperties.Channel> channels = channelSelector.select(
                null,
                request.channelIds(),
                Integer.MAX_VALUE,
                null,
                RoutingMode.PRIORITY
        );
        List<ChannelAttempt> attempts = new ArrayList<>();
        List<GatewayResponse> responses = new ArrayList<>();

        for (PaymentGatewayProperties.Channel channel : channels) {
            try {
                GatewayResponse response = provider(channel).queryComplaints(channel, request);
                boolean success = response.status() != PaymentStatus.FAILED;
                attempts.add(new ChannelAttempt(channel.getId(), success, response.code(), response.message()));
                responses.add(response);
            } catch (GatewayException ex) {
                attempts.add(new ChannelAttempt(channel.getId(), false, ex.code(), ex.getMessage()));
            } catch (RuntimeException ex) {
                attempts.add(new ChannelAttempt(channel.getId(), false, "COMPLAINT_QUERY_EXCEPTION", ex.getMessage()));
            }
        }

        long successCount = attempts.stream().filter(ChannelAttempt::success).count();
        Map<String, Object> raw = Map.of(
                "beginTime", request.beginTime(),
                "endTime", request.endTime(),
                "channels", channels.stream().map(PaymentGatewayProperties.Channel::getId).toList(),
                "responses", responses
        );
        return new GatewayResponse(
                null,
                successCount > 0 ? PaymentStatus.SUCCESS : PaymentStatus.FAILED,
                successCount > 0 ? "COMPLAINT_QUERY_ALL_CHANNELS" : "ALL_CHANNEL_COMPLAINT_QUERY_FAILED",
                "已查询 " + attempts.size() + " 个通道，成功 " + successCount + " 个",
                null,
                null,
                null,
                null,
                raw,
                List.copyOf(attempts)
        );
    }

    public GatewayResponse onboard(OnboardingRequest request) {
        return execute(PaymentProduct.ALIPAY_DIRECT, request.channelIds(), null, null, channel -> provider(channel).onboard(channel, request));
    }

    private GatewayResponse execute(
            PaymentProduct product,
            Collection<String> requestedChannelIds,
            BigDecimal amount,
            RoutingMode routingMode,
            Function<PaymentGatewayProperties.Channel, GatewayResponse> operation
    ) {
        int maxAttempts = Math.max(1, properties.getRouting().getMaxAttempts());
        RoutingMode mode = routingMode == null ? properties.getRouting().getMode() : routingMode;
        List<PaymentGatewayProperties.Channel> channels = channelSelector.select(
                product,
                requestedChannelIds,
                maxAttempts,
                amount,
                mode
        );
        List<ChannelAttempt> attempts = new ArrayList<>();
        GatewayException lastGatewayException = null;
        RuntimeException lastRuntimeException = null;

        for (PaymentGatewayProperties.Channel channel : channels) {
            try {
                GatewayResponse response = operation.apply(channel);
                boolean success = response.status() != PaymentStatus.FAILED;
                attempts.add(new ChannelAttempt(channel.getId(), success, response.code(), response.message()));
                if (success || !properties.getRouting().isFailover()) {
                    return response.withAttempts(attempts);
                }
            } catch (GatewayException ex) {
                lastGatewayException = ex;
                attempts.add(new ChannelAttempt(channel.getId(), false, ex.code(), ex.getMessage()));
                if (!properties.getRouting().isFailover()) {
                    break;
                }
            } catch (RuntimeException ex) {
                lastRuntimeException = ex;
                attempts.add(new ChannelAttempt(channel.getId(), false, "ROUTE_EXCEPTION", ex.getMessage()));
                if (!properties.getRouting().isFailover()) {
                    break;
                }
            }
        }

        String code = lastGatewayException != null ? lastGatewayException.code() : "ALL_CHANNELS_FAILED";
        String message = lastGatewayException != null
                ? lastGatewayException.getMessage()
                : lastRuntimeException != null ? lastRuntimeException.getMessage() : "All payment channels failed";
        return new GatewayResponse(
                null,
                PaymentStatus.FAILED,
                code,
                message,
                null,
                null,
                null,
                null,
                Map.of(),
                List.copyOf(attempts)
        );
    }

    private PaymentProvider provider(PaymentGatewayProperties.Channel channel) {
        String providerCode = "ALIPAY_DIRECT".equals(channel.getProvider()) ? "ALIPAY" : channel.getProvider();
        PaymentProvider provider = providers.get(providerCode);
        if (provider == null) {
            throw new GatewayException("UNSUPPORTED_PROVIDER", "Unsupported provider: " + channel.getProvider());
        }
        return provider;
    }

    private void recordOrder(PayCreateRequest request, GatewayResponse response) {
        if (response.status() == PaymentStatus.FAILED || request.outTradeNo() == null || request.outTradeNo().isBlank()) {
            return;
        }
        orderService.recordPaymentCreated(
                request.outTradeNo(),
                response.tradeNo(),
                response.channelId(),
                extraText(request.extra(), "merchantId", "M10001"),
                extraText(request.extra(), "merchantName", "默认商户"),
                request.product().label(),
                request.totalAmount(),
                request.product() == PaymentProduct.ALIPAY_PREAUTH
        );
    }

    private static String extraText(Map<String, Object> extra, String key, String fallback) {
        if (extra == null) {
            return fallback;
        }
        Object value = extra.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private static Map<String, Object> royaltyParameter(ProfitSharingBatchRequest request, DemoOrderView order) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("royalty_type", "transfer");
        parameter.put("trans_in_type", firstText(request.transInType(), "loginName"));
        parameter.put("trans_in", request.transIn());
        parameter.put("amount", amount(firstAmount(request.amount(), order.amount())));
        parameter.put("desc", firstText(request.desc(), "通道批量分账 " + order.outTradeNo()));
        return parameter;
    }

    private static String outRequestNo(ProfitSharingBatchRequest request, DemoOrderView order) {
        String prefix = firstText(request.outRequestNoPrefix(), "PS");
        return prefix + "_" + order.outTradeNo();
    }

    private static BigDecimal firstAmount(BigDecimal preferred, BigDecimal fallback) {
        return preferred == null ? fallback : preferred;
    }

    private static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private MerchantRouting merchantRouting(PayCreateRequest request) {
        String merchantId = extraText(request.extra(), "merchantId", "");
        if (merchantId.isBlank()) {
            return null;
        }
        return merchantService.routing(merchantId);
    }

    private static Collection<String> channelIds(Collection<String> requestChannelIds, MerchantRouting merchantRouting) {
        if (merchantRouting != null && merchantRouting.channelIds() != null && !merchantRouting.channelIds().isEmpty()) {
            if (requestChannelIds == null || requestChannelIds.isEmpty()) {
                return merchantRouting.channelIds();
            }
            return requestChannelIds.stream()
                    .filter(merchantRouting.channelIds()::contains)
                    .toList();
        }
        if (requestChannelIds != null && !requestChannelIds.isEmpty()) {
            return requestChannelIds;
        }
        return requestChannelIds;
    }

    private static RoutingMode routingMode(RoutingMode requestRoutingMode, MerchantRouting merchantRouting) {
        if (requestRoutingMode != null) {
            return requestRoutingMode;
        }
        return merchantRouting == null ? null : merchantRouting.routingMode();
    }
}
