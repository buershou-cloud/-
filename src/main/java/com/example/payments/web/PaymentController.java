package com.example.payments.web;

import com.example.payments.complaint.ComplaintAutoQueryRequest;
import com.example.payments.complaint.ComplaintAutoQueryService;
import com.example.payments.complaint.ComplaintAutoQueryStatus;
import com.example.payments.complaint.ComplaintRecordService;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingActionRequest;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
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
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.onboarding.OnboardingRecordService;
import com.example.payments.sharing.ProfitSharingRelationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentGatewayService paymentGatewayService;
    private final ComplaintAutoQueryService complaintAutoQueryService;
    private final OnboardingRecordService onboardingRecordService;
    private final ComplaintRecordService complaintRecordService;

    public PaymentController(
            PaymentGatewayService paymentGatewayService,
            ComplaintAutoQueryService complaintAutoQueryService,
            OnboardingRecordService onboardingRecordService,
            ComplaintRecordService complaintRecordService
    ) {
        this.paymentGatewayService = paymentGatewayService;
        this.complaintAutoQueryService = complaintAutoQueryService;
        this.onboardingRecordService = onboardingRecordService;
        this.complaintRecordService = complaintRecordService;
    }

    @PostMapping("/pay")
    public GatewayResponse pay(
            @Valid @RequestBody PayCreateRequest request,
            HttpServletRequest servletRequest
    ) {
        Map<String, Object> extra = new LinkedHashMap<>(request.extra() == null ? Map.of() : request.extra());
        extra.putIfAbsent("payer_client_ip", RequestUrlSupport.clientIp(servletRequest));
        String userAgent = servletRequest.getHeader("User-Agent");
        if (userAgent != null && !userAgent.isBlank()) {
            extra.putIfAbsent("user_agent", userAgent.trim());
        }
        return paymentGatewayService.pay(new PayCreateRequest(
                request.product(),
                request.outTradeNo(),
                request.subject(),
                request.totalAmount(),
                request.authCode(),
                request.buyerId(),
                request.buyerOpenId(),
                request.quitUrl(),
                request.timeoutExpress(),
                request.notifyUrl(),
                request.returnUrl(),
                request.appAuthToken(),
                request.routingMode(),
                request.channelIds(),
                extra,
                request.settleInfo(),
                request.royaltyInfo()
        ));
    }

    @PostMapping("/query")
    public GatewayResponse query(@Valid @RequestBody PaymentQueryRequest request) {
        return paymentGatewayService.query(request);
    }

    @PostMapping("/cancel")
    public GatewayResponse cancel(@Valid @RequestBody PaymentCancelRequest request) {
        return paymentGatewayService.cancel(request);
    }

    @PostMapping("/refund")
    public GatewayResponse refund(@Valid @RequestBody RefundCreateRequest request) {
        return paymentGatewayService.refund(request);
    }

    @PostMapping("/preauth-capture")
    public GatewayResponse preauthCapture(@Valid @RequestBody PreauthCaptureRequest request) {
        return paymentGatewayService.preauthCapture(request);
    }

    @PostMapping("/preauth-unfreeze")
    public GatewayResponse preauthUnfreeze(@Valid @RequestBody PreauthUnfreezeRequest request) {
        return paymentGatewayService.preauthUnfreeze(request);
    }

    @PostMapping("/profit-sharing")
    public GatewayResponse profitSharing(@Valid @RequestBody ProfitSharingRequest request) {
        return paymentGatewayService.profitSharing(request);
    }

    @PostMapping("/profit-sharing/channel")
    public ProfitSharingBatchResult profitSharingByChannel(@Valid @RequestBody ProfitSharingBatchRequest request) {
        return paymentGatewayService.profitSharingByChannel(request);
    }

    @PostMapping("/profit-sharing/relations/bind")
    public GatewayResponse bindProfitSharingRelation(@Valid @RequestBody ProfitSharingRelationBindRequest request) {
        return paymentGatewayService.bindProfitSharingRelation(request);
    }

    @PostMapping("/profit-sharing/relations/query")
    public GatewayResponse queryProfitSharingRelations(@RequestBody ProfitSharingRelationQueryRequest request) {
        return paymentGatewayService.queryProfitSharingRelations(request);
    }

    @PostMapping("/profit-sharing/relations/unbind")
    public GatewayResponse unbindProfitSharingRelation(@Valid @RequestBody ProfitSharingRelationBindRequest request) {
        return paymentGatewayService.unbindProfitSharingRelation(request);
    }

    @PostMapping("/profit-sharing/query")
    public GatewayResponse queryProfitSharing(@Valid @RequestBody ProfitSharingQueryRequest request) {
        return paymentGatewayService.queryProfitSharing(request);
    }

    @PostMapping("/profit-sharing/finish")
    public GatewayResponse finishProfitSharing(@Valid @RequestBody ProfitSharingFinishRequest request) {
        return paymentGatewayService.finishProfitSharing(request);
    }

    @PostMapping("/profit-sharing/remaining")
    public GatewayResponse profitSharingRemainingAmount(@Valid @RequestBody ProfitSharingQueryRequest request) {
        return paymentGatewayService.profitSharingRemainingAmount(request);
    }

    @PostMapping("/profit-sharing/return")
    public GatewayResponse returnProfitSharing(@Valid @RequestBody ProfitSharingReturnRequest request) {
        return paymentGatewayService.returnProfitSharing(request);
    }

    @PostMapping("/profit-sharing/return/query")
    public GatewayResponse queryProfitSharingReturn(@Valid @RequestBody ProfitSharingReturnQueryRequest request) {
        return paymentGatewayService.queryProfitSharingReturn(request);
    }

    @GetMapping("/profit-sharing/relations")
    public List<ProfitSharingRelationService.ProfitSharingRelationView> profitSharingRelations(
            @RequestParam(required = false) String channelId
    ) {
        return paymentGatewayService.profitSharingRelations(channelId);
    }

    @PostMapping("/complaints/query")
    public GatewayResponse queryComplaints(@RequestBody ComplaintQueryRequest request) {
        return paymentGatewayService.queryComplaints(request);
    }

    @GetMapping("/complaints/auto")
    public ComplaintAutoQueryStatus complaintAutoStatus() {
        return complaintAutoQueryService.status();
    }

    @GetMapping("/complaints/records")
    public List<ComplaintRecordService.ComplaintRecordView> complaintRecords(@RequestParam(defaultValue = "100") int limit) {
        return complaintRecordService.list(limit);
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

    @PostMapping("/onboarding/query")
    public GatewayResponse queryOnboarding(@Valid @RequestBody OnboardingActionRequest request) {
        return paymentGatewayService.queryOnboarding(request);
    }

    @PostMapping("/onboarding/cancel")
    public GatewayResponse cancelOnboarding(@Valid @RequestBody OnboardingActionRequest request) {
        return paymentGatewayService.cancelOnboarding(request);
    }

    @GetMapping("/onboarding")
    public List<OnboardingRecordService.OnboardingRecordView> onboardingRecords() {
        return onboardingRecordService.list();
    }
}
