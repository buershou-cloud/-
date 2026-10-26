package com.example.payments.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

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

    @Test
    void rejectsCompressedImageBombFromDimensionsBeforeReadingRaster() throws Exception {
        byte[] png = qrPng("281234567890123456");
        // A valid IHDR declaring 64 million pixels, with a tiny compressed body.
        ByteBuffer.wrap(png).putInt(16, 8000).putInt(20, 8000);
        CRC32 checksum = new CRC32();
        checksum.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) checksum.getValue());
        assertThat(png.length).isLessThan(10000);
        assertThatThrownBy(() -> new PaymentCodeDecodeController().decode(
                new MockMultipartFile("file", "large.png", "image/png", png)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void downsamplesOrdinaryLargePhotosAndStillReadsQr() throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode("281234567890123456", BarcodeFormat.QR_CODE, 2400, 2400);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        var response = new PaymentCodeDecodeController().decode(
                new MockMultipartFile("file", "photo.png", "image/png", output.toByteArray()));
        assertThat(response.paymentCode()).isEqualTo("281234567890123456");
    }

    @Test
    void rejectsOversizeFilesBeforeOpeningTheStream() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(8L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> new PaymentCodeDecodeController().decode(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        verify(file, never()).getInputStream();
    }

    @Test
    void concurrentDecodesAreBoundedAndSlotsAreReleased() throws Exception {
        byte[] png = qrPng("281234567890123456");
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        MultipartFile blocking = mock(MultipartFile.class);
        when(blocking.getSize()).thenReturn((long) png.length);
        when(blocking.getInputStream()).thenAnswer(call -> new FilterInputStream(new ByteArrayInputStream(png)) {
            private boolean waiting = true;
            private void waitOnce() throws IOException {
                if (!waiting) return;
                waiting = false;
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out in test");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
            }
            @Override public int read() throws IOException { waitOnce(); return super.read(); }
            @Override public int read(byte[] data, int off, int len) throws IOException {
                waitOnce(); return super.read(data, off, len);
            }
        });
        var controller = new PaymentCodeDecodeController();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controller.decode(blocking));
            var second = executor.submit(() -> controller.decode(blocking));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> controller.decode(blocking))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).paymentCode()).isEqualTo("281234567890123456");
            assertThat(second.get(5, TimeUnit.SECONDS).paymentCode()).isEqualTo("281234567890123456");
        }
        assertThat(controller.decode(new MockMultipartFile("file", png)).paymentCode()).isEqualTo("281234567890123456");
    }

    private static byte[] qrPng(String text) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 280, 280);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", output);
        return output.toByteArray();
    }
}
