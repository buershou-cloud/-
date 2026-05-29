package com.example.payments.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCodeDecodeControllerTest {

    @Test
    void decodesPaymentCodeFromQrImage() throws Exception {
        String paymentCode = "281234567890123456";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "payment-code.png",
                "image/png",
                qrPng(paymentCode)
        );

        PaymentCodeDecodeController.PaymentCodeDecodeResponse response =
                new PaymentCodeDecodeController().decode(file);

        assertThat(response.text()).isEqualTo(paymentCode);
        assertThat(response.paymentCode()).isEqualTo(paymentCode);
    }

    @Test
    void extractsPaymentCodeFromDecodedUrl() {
        String code = PaymentCodeDecodeController.extractPaymentCode(
                "https://pay.example.test/cashier?auth_code=281234567890123456&source=alipay"
        );

        assertThat(code).isEqualTo("281234567890123456");
    }

    private static byte[] qrPng(String text) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 280, 280);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
    }
}
