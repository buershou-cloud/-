package com.example.payments.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class QrCodeController {

    @GetMapping(value = "/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrcode(@RequestParam String text) throws IOException, WriterException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("QR text is required");
        }
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
    }
}
