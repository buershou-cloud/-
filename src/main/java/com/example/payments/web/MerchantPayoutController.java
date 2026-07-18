package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.payout.MerchantPayoutBatchItemRequest;
import com.example.payments.payout.MerchantPayoutBatchItemResult;
import com.example.payments.payout.MerchantPayoutBatchRequest;
import com.example.payments.payout.MerchantPayoutBatchView;
import com.example.payments.payout.MerchantPayoutCreateRequest;
import com.example.payments.payout.MerchantPayoutService;
import com.example.payments.payout.MerchantPayoutView;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/payouts")
public class MerchantPayoutController {

    private static final int BATCH_CONCURRENCY = 4;

    private final MerchantPayoutService payoutService;
    private final AdminAuthService authService;
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(
            BATCH_CONCURRENCY,
            Thread.ofPlatform().name("merchant-payout-", 0).factory()
    );

    public MerchantPayoutController(MerchantPayoutService payoutService, AdminAuthService authService) {
        this.payoutService = payoutService;
        this.authService = authService;
    }

    @GetMapping
    public List<MerchantPayoutView> list(@RequestParam(defaultValue = "100") int limit) {
        return payoutService.list(limit);
    }

    @PostMapping
    public MerchantPayoutView create(
            @Valid @RequestBody MerchantPayoutCreateRequest request,
            HttpServletRequest servletRequest
    ) {
        verifyPaymentPassword(request.paymentPassword());
        return payoutService.create(
                request,
                RequestUrlSupport.douyinPayoutNotifyUrl(servletRequest, request.channelId())
        );
    }

    @PostMapping("/batch")
    public MerchantPayoutBatchView createBatch(
            @Valid @RequestBody MerchantPayoutBatchRequest request,
            HttpServletRequest servletRequest
    ) {
        verifyPaymentPassword(request.paymentPassword());
        rejectDuplicateOutBizNos(request.items());

        List<String> notifyUrls = request.items().stream()
                .map(item -> RequestUrlSupport.douyinPayoutNotifyUrl(servletRequest, item.channelId()))
                .toList();
        List<CompletableFuture<MerchantPayoutBatchItemResult>> futures = new ArrayList<>();
        for (int index = 0; index < request.items().size(); index++) {
            int itemIndex = index;
            MerchantPayoutBatchItemRequest item = request.items().get(index);
            String notifyUrl = notifyUrls.get(index);
            futures.add(CompletableFuture.supplyAsync(
                    () -> createBatchItem(itemIndex, item, request.paymentPassword(), notifyUrl),
                    batchExecutor
            ));
        }

        List<MerchantPayoutBatchItemResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        int processed = (int) results.stream().filter(result -> result.payout() != null).count();
        return new MerchantPayoutBatchView(results.size(), processed, results.size() - processed, results);
    }

    @PostMapping("/{outBizNo}/query")
    public MerchantPayoutView query(@PathVariable String outBizNo) {
        return payoutService.query(outBizNo);
    }

    @PreDestroy
    void shutdownBatchExecutor() {
        batchExecutor.shutdown();
    }

    private MerchantPayoutBatchItemResult createBatchItem(
            int index,
            MerchantPayoutBatchItemRequest item,
            String paymentPassword,
            String notifyUrl
    ) {
        try {
            MerchantPayoutView payout = payoutService.create(item.toCreateRequest(paymentPassword), notifyUrl);
            return new MerchantPayoutBatchItemResult(index, item.outBizNo(), payout, null);
        } catch (RuntimeException ex) {
            return new MerchantPayoutBatchItemResult(index, item.outBizNo(), null, ex.getMessage());
        }
    }

    private void verifyPaymentPassword(String paymentPassword) {
        if (!authService.paymentPasswordConfigured()) {
            throw new IllegalStateException("请先到账户管理中设置代付支付密码");
        }
        if (!authService.verifyPaymentPassword(paymentPassword)) {
            throw new IllegalArgumentException("支付密码不正确");
        }
    }

    private static void rejectDuplicateOutBizNos(List<MerchantPayoutBatchItemRequest> items) {
        Set<String> explicitNumbers = new HashSet<>();
        for (MerchantPayoutBatchItemRequest item : items) {
            if (item.outBizNo() == null || item.outBizNo().isBlank()) {
                continue;
            }
            String normalized = item.outBizNo().trim().toUpperCase(Locale.ROOT);
            if (!explicitNumbers.add(normalized)) {
                throw new IllegalArgumentException("同一批次不能包含重复的代付单号：" + item.outBizNo().trim());
            }
        }
    }
}
