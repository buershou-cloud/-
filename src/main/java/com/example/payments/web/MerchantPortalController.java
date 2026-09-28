package com.example.payments.web;

import com.example.payments.auth.AdminAuthService;
import com.example.payments.auth.AdminSession;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.MerchantLoginRequest;
import com.example.payments.merchant.MerchantPortalView;
import com.example.payments.merchant.MerchantSession;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/merchant-portal")
public class MerchantPortalController {

    private final DemoMerchantService merchantService;
    private final DemoOrderService orderService;
    private final AdminAuthService adminAuthService;

    public MerchantPortalController(DemoMerchantService merchantService, DemoOrderService orderService,
            AdminAuthService adminAuthService) {
        this.merchantService = merchantService;
        this.orderService = orderService;
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public MerchantPortalView login(
            @RequestBody MerchantLoginRequest request,
            HttpServletRequest servletRequest
    ) {
        boolean demoLogin = Boolean.TRUE.equals(request.demoLogin())
                && AdminSession.isAuthenticated(servletRequest)
                && adminAuthService.hasUsername(AdminSession.username(servletRequest));
        DemoMerchantView merchant = merchantService.login(request.merchantId(), request.md5Key(), demoLogin);
        MerchantSession.login(servletRequest, merchant.merchantId());
        List<DemoOrderView> orders = orderService.byMerchant(merchant.merchantId());
        return new MerchantPortalView(merchant, orders, RequestUrlSupport.apiBase(servletRequest));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        MerchantSession.logout(request);
        return Map.of("message", "商户已退出");
    }
}
