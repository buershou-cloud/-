package com.example.payments.web;

import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final DemoOrderService orderService;

    public OrderController(DemoOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/recent")
    public List<DemoOrderView> recent(
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String outTradeNo,
            @RequestParam(required = false) String tradeNo,
            @RequestParam(required = false) String channelId
    ) {
        return orderService.search(beginTime, endTime, outTradeNo, tradeNo, channelId);
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
    public DemoOrderView convertPreauthToPay(@PathVariable String outTradeNo) {
        return orderService.convertPreauthToPay(outTradeNo);
    }

    @DeleteMapping("/{outTradeNo}")
    public DemoOrderView delete(@PathVariable String outTradeNo) {
        return orderService.delete(outTradeNo);
    }
}
