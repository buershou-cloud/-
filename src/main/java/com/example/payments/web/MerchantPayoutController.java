package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.payout.MerchantPayoutCreateRequest;
import com.example.payments.payout.MerchantPayoutService;
import com.example.payments.payout.MerchantPayoutView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payouts")
public class MerchantPayoutController {

    private final MerchantPayoutService payoutService;
    private final AdminAuthService authService;

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
        if (!authService.paymentPasswordConfigured()) {
            throw new IllegalStateException("请先到账户管理中设置代付支付密码");
        }
        if (!authService.verifyPaymentPassword(request.paymentPassword())) {
            throw new IllegalArgumentException("支付密码不正确");
        }
        return payoutService.create(
                request,
                RequestUrlSupport.douyinPayoutNotifyUrl(servletRequest, request.channelId())
        );
    }

    @PostMapping("/{outBizNo}/query")
    public MerchantPayoutView query(@PathVariable String outBizNo) {
        return payoutService.query(outBizNo);
    }
}
