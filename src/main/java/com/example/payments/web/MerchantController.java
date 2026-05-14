package com.example.payments.web;

import com.example.payments.merchant.DemoMerchantCreateRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantUpdateRequest;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.MerchantSignModeRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final DemoMerchantService merchantService;

    public MerchantController(DemoMerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping
    public List<DemoMerchantView> list() {
        return merchantService.list();
    }

    @PostMapping
    public DemoMerchantView create(@RequestBody DemoMerchantCreateRequest request) {
        return merchantService.create(request);
    }

    @GetMapping("/{merchantId}")
    public DemoMerchantView detail(@PathVariable String merchantId) {
        return merchantService.detail(merchantId);
    }

    @PatchMapping("/{merchantId}")
    public DemoMerchantView update(
            @PathVariable String merchantId,
            @RequestBody DemoMerchantUpdateRequest request
    ) {
        return merchantService.update(merchantId, request);
    }

    @DeleteMapping("/{merchantId}")
    public DemoMerchantView delete(@PathVariable String merchantId) {
        return merchantService.delete(merchantId);
    }

    @PostMapping("/{merchantId}/settle")
    public DemoMerchantView settle(@PathVariable String merchantId) {
        return merchantService.settle(merchantId);
    }

    @PostMapping("/{merchantId}/reset-md5")
    public DemoMerchantView resetMd5(@PathVariable String merchantId) {
        return merchantService.resetMd5(merchantId);
    }

    @PostMapping("/{merchantId}/generate-rsa2")
    public DemoMerchantView generateRsa2(@PathVariable String merchantId) {
        return merchantService.generateRsa2(merchantId);
    }

    @PostMapping("/{merchantId}/sign-mode")
    public DemoMerchantView updateSignMode(
            @PathVariable String merchantId,
            @RequestBody MerchantSignModeRequest request
    ) {
        return merchantService.updateSignMode(merchantId, request.signMode());
    }
}
