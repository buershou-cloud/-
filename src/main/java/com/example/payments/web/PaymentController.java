package com.example.payments.web;

import com.example.payments.complaint.ComplaintAutoQueryRequest;
import com.example.payments.complaint.ComplaintAutoQueryService;
import com.example.payments.complaint.ComplaintAutoQueryStatus;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.ProfitSharingBatchRequest;
import com.example.payments.domain.ProfitSharingBatchResult;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;
import com.example.payments.gateway.PaymentGatewayService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentGatewayService paymentGatewayService;
    private final ComplaintAutoQueryService complaintAutoQueryService;

    public PaymentController(
            PaymentGatewayService paymentGatewayService,
            ComplaintAutoQueryService complaintAutoQueryService
    ) {
        this.paymentGatewayService = paymentGatewayService;
        this.complaintAutoQueryService = complaintAutoQueryService;
    }

    @PostMapping("/pay")
    public GatewayResponse pay(@Valid @RequestBody PayCreateRequest request) {
        return paymentGatewayService.pay(request);
    }

    @PostMapping("/query")
    public GatewayResponse query(@Valid @RequestBody PaymentQueryRequest request) {
        return paymentGatewayService.query(request);
    }

    @PostMapping("/refund")
    public GatewayResponse refund(@Valid @RequestBody RefundCreateRequest request) {
        return paymentGatewayService.refund(request);
    }

    @PostMapping("/profit-sharing")
    public GatewayResponse profitSharing(@Valid @RequestBody ProfitSharingRequest request) {
        return paymentGatewayService.profitSharing(request);
    }

    @PostMapping("/profit-sharing/channel")
    public ProfitSharingBatchResult profitSharingByChannel(@Valid @RequestBody ProfitSharingBatchRequest request) {
        return paymentGatewayService.profitSharingByChannel(request);
    }

    @PostMapping("/complaints/query")
    public GatewayResponse queryComplaints(@RequestBody ComplaintQueryRequest request) {
        return paymentGatewayService.queryComplaints(request);
    }

    @GetMapping("/complaints/auto")
    public ComplaintAutoQueryStatus complaintAutoStatus() {
        return complaintAutoQueryService.status();
    }

    @PatchMapping("/complaints/auto")
    public ComplaintAutoQueryStatus configureComplaintAutoQuery(@RequestBody ComplaintAutoQueryRequest request) {
        return complaintAutoQueryService.configure(request);
    }

    @PostMapping("/complaints/auto/run")
    public ComplaintAutoQueryStatus runComplaintAutoQuery(@RequestBody(required = false) ComplaintAutoQueryRequest request) {
        complaintAutoQueryService.configure(request);
        return complaintAutoQueryService.runNow();
    }

    @PostMapping("/onboarding")
    public GatewayResponse onboard(@Valid @RequestBody OnboardingRequest request) {
        return paymentGatewayService.onboard(request);
    }
}
