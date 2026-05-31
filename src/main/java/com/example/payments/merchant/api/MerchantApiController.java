package com.example.payments.merchant.api;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/merchant-api")
public class MerchantApiController {

    private final PaymentGatewayService paymentGatewayService;
    private final DemoMerchantService merchantService;
    private final DemoOrderService orderService;
    private final MerchantSignatureService signatureService;

    public MerchantApiController(
            PaymentGatewayService paymentGatewayService,
            DemoMerchantService merchantService,
            DemoOrderService orderService,
            MerchantSignatureService signatureService
    ) {
        this.paymentGatewayService = paymentGatewayService;
        this.merchantService = merchantService;
        this.orderService = orderService;
        this.signatureService = signatureService;
    }

    @PostMapping("/pay")
    public MerchantApiResponse<GatewayResponse> pay(@Valid @RequestBody MerchantApiPayRequest request) {
        DemoMerchantView merchant = signatureService.verify(request);
        List<String> channelIds = safeChannelIds(merchant, request.channelIds());
        GatewayResponse response = paymentGatewayService.pay(request.toPayCreateRequest(merchant, channelIds));
        return signatureService.success(merchant, request.signType(), response);
    }

    @PostMapping("/query")
    public MerchantApiResponse<GatewayResponse> query(@Valid @RequestBody MerchantApiQueryRequest request) {
        DemoMerchantView merchant = signatureService.verify(request);
        orderService.ensureMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        GatewayResponse response = paymentGatewayService.query(request.toPaymentQueryRequest(safeChannelIds(merchant, request.channelIds())));
        return signatureService.success(merchant, request.signType(), response);
    }

    @PostMapping("/cancel")
    public MerchantApiResponse<GatewayResponse> cancel(@Valid @RequestBody MerchantApiCancelRequest request) {
        DemoMerchantView merchant = signatureService.verify(request);
        orderService.ensureMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        GatewayResponse response = paymentGatewayService.cancel(request.toPaymentCancelRequest(safeChannelIds(merchant, request.channelIds())));
        return signatureService.success(merchant, request.signType(), response);
    }

    @PostMapping("/refund")
    public MerchantApiResponse<GatewayResponse> refund(@Valid @RequestBody MerchantApiRefundRequest request) {
        DemoMerchantView merchant = signatureService.verify(request);
        orderService.ensureMerchantOrder(merchant.merchantId(), request.outTradeNo(), request.tradeNo());
        GatewayResponse response = paymentGatewayService.refund(request.toRefundCreateRequest(safeChannelIds(merchant, request.channelIds())));
        return signatureService.success(merchant, request.signType(), response);
    }

    private List<String> safeChannelIds(DemoMerchantView merchant, List<String> requestedChannelIds) {
        Set<String> boundChannelIds = merchantService.routing(merchant.merchantId()).channelIds();
        if (boundChannelIds == null || boundChannelIds.isEmpty()) {
            return requestedChannelIds;
        }
        if (requestedChannelIds == null || requestedChannelIds.isEmpty()) {
            return List.copyOf(boundChannelIds);
        }
        List<String> filtered = requestedChannelIds.stream()
                .filter(boundChannelIds::contains)
                .toList();
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("No requested channel is enabled for this merchant");
        }
        return filtered;
    }
}
