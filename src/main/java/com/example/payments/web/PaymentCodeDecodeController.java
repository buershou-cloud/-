package com.example.payments.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/payment-code")
public class PaymentCodeDecodeController {

    private static final Pattern QUERY_CODE = Pattern.compile(
            "(?:auth_code|authCode|payment_code|paymentCode|code)=([0-9]{10,32})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)(\\d{10,32})(?!\\d)");
    private static final Map<DecodeHintType, Object> HINTS = decodeHints();
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SOURCE_PIXELS = 20_000_000L;
    private static final long MAX_DECODE_PIXELS = 2_000_000L;
    private static final int MAX_DIMENSION = 8192;
    // Reject excess concurrent work instead of queuing unbounded image allocations.
    private final Semaphore decodeSlots = new Semaphore(2);

    @PostMapping("/decode")
    public PaymentCodeDecodeResponse decode(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传付款码照片");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 8 MB");
        }
        if (!decodeSlots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "图片识别繁忙，请稍后重试");
        }
        try {
            BufferedImage image = readBoundedImage(file);
            try {
                String text = decodeImage(image);
                if (text == null || text.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到二维码或条形码，请换一张清晰照片");
                }
                return new PaymentCodeDecodeResponse(text, extractPaymentCode(text));
            } finally {
                image.flush();
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取图片，请上传完整的 JPG/PNG 图片", ex);
        } finally {
            decodeSlots.release();
        }
    }

    private static BufferedImage readBoundedImage(MultipartFile file) throws IOException {
        try (var input = file.getInputStream(); var images = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(images);
            if (!readers.hasNext()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取图片，请上传 JPG/PNG 图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(images, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                // Read dimensions before allocating/decompressing the raster (compressed files can be tiny).
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片尺寸过大，请裁剪到付款码区域后重试");
                }
                int sample = 1;
                while ((long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample) > MAX_DECODE_PIXELS) {
                    sample++;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sample, sample, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    static String decodeImage(BufferedImage image) {
        String text = decodeCandidate(image);
        if (!text.isBlank()) return text;
        for (int degrees : new int[]{90, 180, 270}) {
            BufferedImage candidate = rotate(image, degrees);
            try {
                text = decodeCandidate(candidate);
                if (!text.isBlank()) return text;
            } finally {
                candidate.flush();
            }
        }
        return "";
    }

    static String extractPaymentCode(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", "");
        if (compact.matches("\\d{10,32}")) {
            return compact;
        }
        Matcher queryMatcher = QUERY_CODE.matcher(text);
        if (queryMatcher.find()) {
            return queryMatcher.group(1);
        }
        Matcher digitsMatcher = DIGITS.matcher(text);
        return digitsMatcher.find() ? digitsMatcher.group(1) : "";
    }

    private static String decodeCandidate(BufferedImage image) {
        try {
            BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
            return read(source);
        } catch (NotFoundException ignored) {
            try {
                BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
                return read(source.invert());
            } catch (NotFoundException ignoredAgain) {
                return "";
            }
        }
    }

    private static String read(LuminanceSource source) throws NotFoundException {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result result = new MultiFormatReader().decode(bitmap, HINTS);
        return result.getText();
    }

    private static BufferedImage rotate(BufferedImage image, int degrees) {
        if (degrees == 180) {
            BufferedImage rotated = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rotated.createGraphics();
            graphics.rotate(Math.PI, image.getWidth() / 2.0, image.getHeight() / 2.0);
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            return rotated;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage rotated = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        if (degrees == 90) {
            graphics.translate(height, 0);
            graphics.rotate(Math.PI / 2);
        } else {
            graphics.translate(0, width);
            graphics.rotate(-Math.PI / 2);
        }
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return rotated;
    }

    private static Map<DecodeHintType, Object> decodeHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.UPC_A,
                BarcodeFormat.ITF,
                BarcodeFormat.PDF_417,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC
        ));
        return hints;
    }

    public record PaymentCodeDecodeResponse(String text, String paymentCode) {
    }
}
