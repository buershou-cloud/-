package com.example.payments.gateway;

import com.example.payments.channel.ChannelSelector;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ChannelAttempt;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.OnboardingActionRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.ProfitSharingBatchItem;
import com.example.payments.domain.ProfitSharingBatchRequest;
import com.example.payments.domain.ProfitSharingBatchResult;
import com.example.payments.domain.ProfitSharingFinishRequest;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRelationQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.ProfitSharingReturnQueryRequest;
import com.example.payments.domain.ProfitSharingReturnRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.domain.RoutingMode;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.MerchantRouting;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.sharing.ProfitSharingRelationService;
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
    private final OnboardingRecordService onboardingRecordService;
    private final ComplaintRecordService complaintRecordService;
    private final ProfitSharingRelationService profitSharingRelationService;

    public PaymentGatewayService(
            PaymentGatewayProperties properties,
            ChannelSelector channelSelector,
            List<PaymentProvider> providers,
            DemoOrderService orderService,
            DemoMerchantService merchantService,
            OnboardingRecordService onboardingRecordService,
            ComplaintRecordService complaintRecordService,
            ProfitSharingRelationService profitSharingRelationService
    ) {
        this.properties = properties;
        this.channelSelector = channelSelector;
        this.providers = providers.stream().collect(Collectors.toMap(PaymentProvider::providerCode, Function.identity()));
        this.orderService = orderService;
        this.merchantService = merchantService;
        this.onboardingRecordService = onboardingRecordService;
        this.complaintRecordService = complaintRecordService;
        this.profitSharingRelationService = profitSharingRelationService;
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
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> provider(channel).query(channel, request));
        syncLocalPaymentStatus(request.outTradeNo(), response);
        return response;
    }

    public GatewayResponse cancel(PaymentCancelRequest request) {
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> provider(channel).cancel(channel, request));
        syncLocalPaymentStatus(request.outTradeNo(), response);
        return response;
    }

    public GatewayResponse refund(RefundCreateRequest request) {
        orderService.ensureRefundable(request.outTradeNo(), request.tradeNo(), request.refundAmount());
        Map<String, Object> refundExtra = new LinkedHashMap<>(request.extra() == null ? Map.of() : request.extra());
        refundExtra.putIfAbsent(
                "douyin_total_amount_fen",
                orderService.amountByIdentifier(request.outTradeNo(), request.tradeNo())
                        .movePointRight(2)
                        .setScale(0, RoundingMode.UNNECESSARY)
                        .longValueExact()
        );
        RefundCreateRequest prepared = new RefundCreateRequest(
                request.outTradeNo(),
                request.tradeNo(),
                request.refundAmount(),
                request.outRequestNo(),
                request.refundReason(),
                request.appAuthToken(),
                request.channelIds(),
                refundExtra
        );
        GatewayResponse response = execute(null, prepared.channelIds(), null, null, channel -> provider(channel).refund(channel, prepared));
        if (response.status() != PaymentStatus.FAILED) {
            orderService.recordRefund(prepared, response);
        }
        return response;
    }

    public GatewayResponse preauthCapture(PreauthCaptureRequest request) {
        String preauthProductCode = preauthProductCode(request.preauthOutTradeNo(), request.extra());
        PreauthCaptureRequest prepared = request.withAuthNo(resolvePreauthAuthNo(
                request.authNo(),
                request.preauthOutTradeNo(),
                request.channelIds(),
                request.appAuthToken(),
                request.extra()
        )).withExtra(withPreauthProductCode(request.extra(), preauthProductCode));
        GatewayResponse response = execute(
                null,
                prepared.channelIds(),
                prepared.totalAmount(),
                null,
                channel -> provider(channel).preauthCapture(channel, prepared)
        );
        if (response.status() != PaymentStatus.FAILED && hasText(prepared.preauthOutTradeNo())) {
            orderService.convertPreauthToPay(prepared.preauthOutTradeNo(), firstText(response.tradeNo(), prepared.outTradeNo()));
        }
        return response;
    }

    public GatewayResponse preauthUnfreeze(PreauthUnfreezeRequest request) {
        PreauthUnfreezeRequest prepared = request.withAuthNo(resolvePreauthAuthNo(
                request.authNo(),
                request.preauthOutTradeNo(),
                request.channelIds(),
                request.appAuthToken(),
                request.extra()
        ));
        orderService.ensurePreauthUnfreezable(prepared.preauthOutTradeNo(), prepared.authNo(), prepared.amount());
        GatewayResponse response = execute(
                null,
                prepared.channelIds(),
                prepared.amount(),
                null,
                channel -> provider(channel).preauthUnfreeze(channel, prepared)
        );
        if (response.status() == PaymentStatus.SUCCESS && hasText(prepared.preauthOutTradeNo())) {
            orderService.recordPreauthUnfreeze(prepared, response);
        }
        return response;
    }

    public GatewayResponse profitSharing(ProfitSharingRequest request) {
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> {
            validateProfitSharingRelations(channel.getId(), request.royaltyParameters());
            return provider(channel).profitSharing(channel, request);
        });
        if (response.status() == PaymentStatus.SUCCESS && hasText(request.outTradeNo())) {
            orderService.markProfitShared(request.outTradeNo());
        }
        return response;
    }

    public GatewayResponse bindProfitSharingRelation(ProfitSharingRelationBindRequest request) {
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> provider(channel).bindProfitSharingRelation(channel, request));
        profitSharingRelationService.recordBind(request, response);
        return response;
    }

    public GatewayResponse queryProfitSharingRelations(ProfitSharingRelationQueryRequest request) {
        GatewayResponse response = execute(null, request.channelIds(), null, null, channel -> provider(channel).queryProfitSharingRelations(channel, request));
        profitSharingRelationService.recordQuery(request, response);
        return response;
    }

    public GatewayResponse unbindProfitSharingRelation(ProfitSharingRelationBindRequest request) {
        GatewayResponse response = execute(
                null,
                request.channelIds(),
                null,
                null,
                channel -> provider(channel).unbindProfitSharingRelation(channel, request)
        );
        profitSharingRelationService.recordUnbind(request, response);
        return response;
    }

    public GatewayResponse queryProfitSharing(ProfitSharingQueryRequest request) {
        GatewayResponse response = execute(
                null,
                request.channelIds(),
                null,
                null,
                channel -> provider(channel).queryProfitSharing(channel, request)
        );
        if (response.status() == PaymentStatus.SUCCESS && hasText(request.outTradeNo())) {
            orderService.markProfitShared(request.outTradeNo());
        }
        return response;
    }

    public GatewayResponse finishProfitSharing(ProfitSharingFinishRequest request) {
        return execute(
                null,
                request.channelIds(),
                null,
                null,
                channel -> provider(channel).finishProfitSharing(channel, request)
        );
    }

    public GatewayResponse profitSharingRemainingAmount(ProfitSharingQueryRequest request) {
        return execute(
                null,
                request.channelIds(),
                null,
                null,
                channel -> provider(channel).profitSharingRemainingAmount(channel, request)
        );
    }

    public GatewayResponse returnProfitSharing(ProfitSharingReturnRequest request) {
        return execute(
                null,
                request.channelIds(),
                request.amount(),
                null,
                channel -> provider(channel).returnProfitSharing(channel, request)
        );
    }

    public GatewayResponse queryProfitSharingReturn(ProfitSharingReturnQueryRequest request) {
        return execute(
                null,
                request.channelIds(),
                null,
                null,
                channel -> provider(channel).queryProfitSharingReturn(channel, request)
        );
    }

    public List<ProfitSharingRelationService.ProfitSharingRelationView> profitSharingRelations(String channelId) {
        return profitSharingRelationService.list(channelId);
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
                "通道 " + request.channelId() + " 已处理 " + orders.size() + " 笔订单，已提交 " + success + " 笔，失败 " + failed + " 笔",
                List.copyOf(items)
        );
    }

    public GatewayResponse queryComplaints(ComplaintQueryRequest request) {
        ComplaintQueryRequest safeRequest = safeComplaintRequest(request);
        GatewayResponse response = execute(null, safeRequest.channelIds(), null, null,
                channel -> provider(channel).queryComplaints(channel, safeRequest));
        List<ComplaintRecordService.ComplaintRecordView> records = complaintRecordService.record(response);
        return withComplaintRecords(response, records);
    }

    public GatewayResponse queryComplaintsAllChannels(ComplaintQueryRequest request) {
        ComplaintQueryRequest safeRequest = safeComplaintRequest(request);
        List<PaymentGatewayProperties.Channel> channels = channelSelector.select(
                null,
                safeRequest.channelIds(),
                Integer.MAX_VALUE,
                null,
                RoutingMode.PRIORITY
        );
        List<ChannelAttempt> attempts = new ArrayList<>();
        List<GatewayResponse> responses = new ArrayList<>();
        List<ComplaintRecordService.ComplaintRecordView> records = new ArrayList<>();

        for (PaymentGatewayProperties.Channel channel : channels) {
            try {
                GatewayResponse response = provider(channel).queryComplaints(channel, safeRequest);
                boolean success = response.status() != PaymentStatus.FAILED;
                attempts.add(new ChannelAttempt(channel.getId(), success, response.code(), response.message()));
                responses.add(response);
                records.addAll(complaintRecordService.record(response));
            } catch (GatewayException ex) {
                attempts.add(new ChannelAttempt(channel.getId(), false, ex.code(), ex.getMessage()));
                GatewayResponse failure = complaintFailure(channel.getId(), ex.code(), ex.getMessage());
                responses.add(failure);
                records.addAll(complaintRecordService.record(failure));
            } catch (RuntimeException ex) {
                attempts.add(new ChannelAttempt(channel.getId(), false, "COMPLAINT_QUERY_EXCEPTION", ex.getMessage()));
                GatewayResponse failure = complaintFailure(channel.getId(), "COMPLAINT_QUERY_EXCEPTION", ex.getMessage());
                responses.add(failure);
                records.addAll(complaintRecordService.record(failure));
            }
        }

        long successCount = attempts.stream().filter(ChannelAttempt::success).count();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("beginTime", safeRequest.beginTime());
        raw.put("endTime", safeRequest.endTime());
        raw.put("channels", channels.stream().map(PaymentGatewayProperties.Channel::getId).toList());
        raw.put("responses", responses);
        raw.put("complaintRecords", records);
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

    private static ComplaintQueryRequest safeComplaintRequest(ComplaintQueryRequest request) {
        if (request != null) {
            return request;
        }
        return new ComplaintQueryRequest(null, null, null, 1, 10, null, null, List.of(), Map.of());
    }

    private GatewayResponse complaintFailure(String channelId, String code, String message) {
        return new GatewayResponse(
                channelId,
                PaymentStatus.FAILED,
                code,
                message,
                null,
                null,
                null,
                null,
                complaintFailureRaw(channelId, code, message),
                List.of(new ChannelAttempt(channelId, false, code, message))
        );
    }

    private Map<String, Object> complaintFailureRaw(String channelId, String code, String message) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("channelId", channelId);
        raw.put("code", code);
        raw.put("message", message == null ? "" : message);
        return raw;
    }

    private GatewayResponse withComplaintRecords(
            GatewayResponse response,
            List<ComplaintRecordService.ComplaintRecordView> records
    ) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (response.raw() != null) {
            raw.putAll(response.raw());
        }
        raw.put("complaintRecords", records);
        return new GatewayResponse(
                response.channelId(),
                response.status(),
                response.code(),
                response.message(),
                response.outTradeNo(),
                response.tradeNo(),
                response.qrCode(),
                response.redirectHtml(),
                response.redirectUrl(),
                raw,
                response.attempts()
        );
    }

    public GatewayResponse onboard(OnboardingRequest request) {
        GatewayResponse response = execute(PaymentProduct.ALIPAY_DIRECT, request.channelIds(), null, null, channel -> provider(channel).onboard(channel, request));
        onboardingRecordService.record(request, response);
        return response;
    }

    public GatewayResponse queryOnboarding(OnboardingActionRequest request) {
        OnboardingRequest actionRequest = onboardingActionRequest(request, properties.getOperations().getOnboardingQueryMethod());
        GatewayResponse response = execute(
                PaymentProduct.ALIPAY_DIRECT,
                actionRequest.channelIds(),
                null,
                null,
                channel -> provider(channel).onboard(channel, actionRequest)
        );
        onboardingRecordService.record(actionRequest, response);
        return response;
    }

    public GatewayResponse cancelOnboarding(OnboardingActionRequest request) {
        OnboardingRequest actionRequest = onboardingActionRequest(request, properties.getOperations().getOnboardingCancelMethod());
        GatewayResponse response = execute(
                PaymentProduct.ALIPAY_DIRECT,
                actionRequest.channelIds(),
                null,
                null,
                channel -> provider(channel).onboard(channel, actionRequest)
        );
        onboardingRecordService.record(actionRequest, response);
        return response;
    }

    private OnboardingRequest onboardingActionRequest(OnboardingActionRequest request, String defaultMethod) {
        Map<String, Object> payload = new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
        payload.putIfAbsent("external_id", request.outBizNo());
        return new OnboardingRequest(
                request.outBizNo(),
                firstText(request.method(), defaultMethod),
                request.appAuthToken(),
                request.channelIds(),
                payload
        );
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
        List<ChannelAttempt> attempts = new ArrayList<>();
        List<PaymentGatewayProperties.Channel> channels;
        try {
            channels = channelSelector.select(
                    product,
                    requestedChannelIds,
                    maxAttempts,
                    amount,
                    mode
            );
        } catch (IllegalStateException ex) {
            return new GatewayResponse(
                    null,
                    PaymentStatus.FAILED,
                    "NO_MATCHING_CHANNEL",
                    ex.getMessage(),
                    null,
                    null,
                    null,
                    null,
                    routeFailureRaw(product, requestedChannelIds, amount, mode),
                    List.of()
            );
        }
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

        ChannelAttempt lastAttempt = attempts.isEmpty() ? null : attempts.getLast();
        String code = lastGatewayException != null
                ? lastGatewayException.code()
                : firstText(lastAttempt == null ? null : lastAttempt.code(), "ALL_CHANNELS_FAILED");
        String message = lastGatewayException != null
                ? lastGatewayException.getMessage()
                : lastRuntimeException != null
                        ? lastRuntimeException.getMessage()
                        : firstText(lastAttempt == null ? null : lastAttempt.message(), "All payment channels failed");
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

    private static Map<String, Object> routeFailureRaw(
            PaymentProduct product,
            Collection<String> requestedChannelIds,
            BigDecimal amount,
            RoutingMode routingMode
    ) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("product", product == null ? null : product.name());
        raw.put("requestedChannelIds", requestedChannelIds == null ? List.of() : List.copyOf(requestedChannelIds));
        raw.put("amount", amount);
        raw.put("routingMode", routingMode == null ? null : routingMode.name());
        return raw;
    }

    private PaymentProvider provider(PaymentGatewayProperties.Channel channel) {
        String providerCode = "ALIPAY_DIRECT".equals(channel.getProvider()) ? "ALIPAY" : channel.getProvider();
        PaymentProvider provider = providers.get(providerCode);
        if (provider == null) {
            throw new GatewayException("UNSUPPORTED_PROVIDER", "Unsupported provider: " + channel.getProvider());
        }
        return provider;
    }

    private String resolvePreauthAuthNo(
            String currentAuthNo,
            String preauthOutTradeNo,
            Collection<String> channelIds,
            String appAuthToken,
            Map<String, Object> extra
    ) {
        if (hasUsableText(currentAuthNo)) {
            return currentAuthNo.trim();
        }
        if (!hasText(preauthOutTradeNo)) {
            throw new IllegalArgumentException("预授权订单号不能为空，无法查询支付宝授权号");
        }
        DemoOrderView order = orderService.view(preauthOutTradeNo.trim());
        String lastMessage = null;
        for (String outRequestNo : preauthOutRequestNoCandidates(preauthOutTradeNo.trim(), order, extra)) {
            Map<String, Object> queryExtra = new LinkedHashMap<>();
            queryExtra.put("out_request_no", outRequestNo);
            queryExtra.put("operation_type", "FREEZE");
            String operationId = mapText(extra, "operation_id");
            if (hasText(operationId)) {
                queryExtra.put("operation_id", operationId.trim());
            }
            GatewayResponse response = execute(
                    null,
                    channelIds,
                    null,
                    null,
                    channel -> provider(channel).preauthQuery(
                            channel,
                            new PaymentQueryRequest(preauthOutTradeNo.trim(), null, appAuthToken, null, queryExtra)
                    )
            );
            lastMessage = firstText(response.message(), lastMessage);
            if (response.status() != PaymentStatus.FAILED && hasUsableText(response.tradeNo())) {
                orderService.recordPreauthAuthNo(preauthOutTradeNo.trim(), response.tradeNo(), response.channelId());
                return response.tradeNo().trim();
            }
        }
        if (hasUsableText(order.tradeNo())) {
            return order.tradeNo().trim();
        }
        throw new IllegalArgumentException(firstText(
                lastMessage,
                "支付宝预授权号为空，无法转支付或解冻；请确认用户已经完成预授权，再查询订单后重试"
        ));
    }

    private String preauthProductCode(String preauthOutTradeNo, Map<String, Object> extra) {
        String requested = firstText(mapText(extra, "preauth_product_code"), mapText(extra, "product_code"));
        if (hasUsableText(requested)) {
            return requested.trim();
        }
        if (hasText(preauthOutTradeNo)) {
            String storedProductCode = orderService.paymentRequestProductCode(preauthOutTradeNo.trim());
            if (hasUsableText(storedProductCode)) {
                return storedProductCode.trim();
            }
            DemoOrderView order = orderService.view(preauthOutTradeNo.trim());
            if (order != null && hasText(order.productName()) && order.productName().toUpperCase().contains("H5")) {
                return "PREAUTH_PAY";
            }
        }
        return "PRE_AUTH";
    }

    private static Map<String, Object> withPreauthProductCode(Map<String, Object> extra, String productCode) {
        Map<String, Object> result = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        result.put("preauth_product_code", firstText(productCode, "PRE_AUTH"));
        return result;
    }

    private static List<String> preauthOutRequestNoCandidates(
            String outTradeNo,
            DemoOrderView order,
            Map<String, Object> extra
    ) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, mapText(extra, "out_request_no"));
        addCandidate(candidates, mapText(extra, "auth_out_request_no"));
        if (order != null && hasText(order.productName()) && order.productName().toUpperCase().contains("H5")) {
            addCandidate(candidates, outTradeNo + "_h5");
            addCandidate(candidates, outTradeNo + "_voucher");
        } else {
            addCandidate(candidates, outTradeNo + "_voucher");
            addCandidate(candidates, outTradeNo + "_h5");
        }
        addCandidate(candidates, outTradeNo);
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String value) {
        if (!hasText(value)) {
            return;
        }
        String text = value.trim();
        if (!candidates.contains(text)) {
            candidates.add(text);
        }
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
                request.subject(),
                request.totalAmount(),
                request.product() == PaymentProduct.ALIPAY_PREAUTH
                        || request.product() == PaymentProduct.ALIPAY_PREAUTH_H5,
                response.status()
        );
        orderService.recordPaymentMetadata(request.outTradeNo(), request, response);
    }

    private void syncLocalPaymentStatus(String outTradeNo, GatewayResponse response) {
        if (!hasText(outTradeNo) || response == null || response.status() == PaymentStatus.FAILED) {
            return;
        }
        try {
            orderService.recordPaymentResult(outTradeNo, response.tradeNo(), response.channelId(), response.status());
        } catch (RuntimeException ignored) {
            // Alipay is authoritative; a local demo order sync error must not hide the gateway response.
        }
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
        if (request.percentage() != null) {
            validatePercentage(request.percentage());
            parameter.put("amount_percentage", percentage(request.percentage()));
        } else {
            parameter.put("amount", amount(firstAmount(request.amount(), order.amount())));
        }
        parameter.put("desc", firstText(request.desc(), "通道批量分账 " + order.outTradeNo()));
        return parameter;
    }

    private void validateProfitSharingRelations(String channelId, List<Map<String, Object>> royaltyParameters) {
        if (royaltyParameters == null || royaltyParameters.isEmpty()) {
            return;
        }
        for (Map<String, Object> parameter : royaltyParameters) {
            String transIn = mapText(parameter, "trans_in");
            if (!hasText(transIn)) {
                continue;
            }
            String transInType = firstText(mapText(parameter, "trans_in_type"), "loginName");
            if (!profitSharingRelationService.isBound(channelId, transInType, transIn)) {
                throw new IllegalArgumentException("收入方账号未绑定分账关系，请先在“分账关系”中添加：" + transIn);
            }
        }
    }

    private static String mapText(Map<String, Object> value, String key) {
        if (value == null) {
            return null;
        }
        Object raw = value.get(key);
        return raw == null ? null : String.valueOf(raw);
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

    private static String percentage(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static void validatePercentage(BigDecimal value) {
        if (value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("分账比例必须大于 0 且不能超过 100%");
        }
    }

    private static String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasUsableText(String value) {
        if (!hasText(value)) {
            return false;
        }
        String text = value.trim();
        return !"-".equals(text)
                && !"null".equalsIgnoreCase(text)
                && !"undefined".equalsIgnoreCase(text);
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
