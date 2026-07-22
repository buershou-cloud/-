package com.example.payments.web;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.gateway.douyin.DouyinPaymentReconciliationService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final DemoOrderService orderService;
    private final PaymentGatewayService paymentGatewayService;
    private final DouyinPaymentReconciliationService douyinReconciliationService;

    public OrderController(
            DemoOrderService orderService,
            PaymentGatewayService paymentGatewayService,
            DouyinPaymentReconciliationService douyinReconciliationService
    ) {
        this.orderService = orderService;
        this.paymentGatewayService = paymentGatewayService;
        this.douyinReconciliationService = douyinReconciliationService;
    }

    @GetMapping("/recent")
    public List<DemoOrderView> recent(
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String outTradeNo,
            @RequestParam(required = false) String tradeNo,
            @RequestParam(required = false) String channelId,
            @RequestParam(defaultValue = "false") boolean refreshStatuses
    ) {
        List<DemoOrderView> orders = orderService.search(beginTime, endTime, outTradeNo, tradeNo, channelId);
        if (refreshStatuses) {
            douyinReconciliationService.reconcileVisibleOrders(orders);
            return orderService.search(beginTime, endTime, outTradeNo, tradeNo, channelId);
        }
        return orders;
    }

    @PostMapping("/{outTradeNo}/complete")
    public DemoOrderView complete(@PathVariable String outTradeNo) {
        return orderService.complete(outTradeNo);
    }

    @PostMapping("/{outTradeNo}/uncomplete")
    public DemoOrderView uncomplete(@PathVariable String outTradeNo) {
        return orderService.uncomplete(outTradeNo);
    }

    @PostMapping("/{outTradeNo}/manual-supplement")
    public DemoOrderView manualSupplement(@PathVariable String outTradeNo) {
        return orderService.manualSupplement(outTradeNo);
    }

    @PostMapping("/{outTradeNo}/refund")
    public DemoOrderView refund(@PathVariable String outTradeNo) {
        return orderService.refund(outTradeNo);
    }

    @PostMapping("/{outTradeNo}/convert-to-pay")
    public PreauthConversionResponse convertPreauthToPay(
            @PathVariable String outTradeNo,
            @Valid @RequestBody PreauthCaptureRequest request
    ) {
        GatewayResponse gateway = paymentGatewayService.preauthCapture(request.withPreauthOutTradeNo(outTradeNo));
        return new PreauthConversionResponse(gateway, orderService.view(outTradeNo));
    }

    @PostMapping("/{outTradeNo}/unfreeze")
    public GatewayResponse unfreezePreauth(
            @PathVariable String outTradeNo,
            @Valid @RequestBody PreauthUnfreezeRequest request
    ) {
        return paymentGatewayService.preauthUnfreeze(request.withPreauthOutTradeNo(outTradeNo));
    }

    @DeleteMapping("/{outTradeNo}")
    public DemoOrderView delete(@PathVariable String outTradeNo) {
        return orderService.delete(outTradeNo);
    }

    public record PreauthConversionResponse(GatewayResponse gateway, DemoOrderView order) {
    }
}
