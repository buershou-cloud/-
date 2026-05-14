package com.example.payments.web;

import com.example.payments.merchant.DemoMerchantCreateRequest;
import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantUpdateRequest;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.merchant.MerchantCashierInfo;
import com.example.payments.merchant.MerchantSignModeRequest;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    @GetMapping("/{merchantId}/cashier-info")
    public MerchantCashierInfo cashierInfo(@PathVariable String merchantId) {
        return MerchantCashierInfo.from(merchantService.detail(merchantId));
    }

    @GetMapping(value = "/{merchantId}/cashier-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] cashierQr(
            @PathVariable String merchantId,
            HttpServletRequest request
    ) throws IOException, WriterException {
        merchantService.detail(merchantId);
        String cashierUrl = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/cashier.html")
                .queryParam("merchantId", merchantId)
                .build()
                .toUriString();

        BitMatrix matrix = new QRCodeWriter().encode(cashierUrl, BarcodeFormat.QR_CODE, 320, 320);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
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
