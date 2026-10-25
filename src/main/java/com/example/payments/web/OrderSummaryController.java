package com.example.payments.web;

import com.example.payments.order.OrderSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderSummaryController {
    private final OrderSummaryService service;

    public OrderSummaryController(OrderSummaryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/orders/summary")
    public OrderSummaryService.Summary summary(@RequestParam String today, @RequestParam String month) {
        return service.summary(today, month);
    }
}
