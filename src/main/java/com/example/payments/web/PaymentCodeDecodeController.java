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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    @PostMapping("/decode")
    public PaymentCodeDecodeResponse decode(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传付款码照片");
        }
        BufferedImage image = ImageIO.read(file.getInputStream());
        if (image == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取图片，请上传 JPG/PNG 图片");
        }
        String text = decodeImage(image);
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到二维码或条形码，请换一张清晰照片");
        }
        return new PaymentCodeDecodeResponse(text, extractPaymentCode(text));
    }

    static String decodeImage(BufferedImage image) {
        for (BufferedImage candidate : imageVariants(image)) {
            String text = decodeCandidate(candidate);
            if (text != null && !text.isBlank()) {
                return text;
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

    private static List<BufferedImage> imageVariants(BufferedImage image) {
        return List.of(
                image,
                rotate(image, 90),
                rotate(image, 180),
                rotate(image, 270)
        );
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
