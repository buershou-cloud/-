package com.example.payments.gateway.alipay;

import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the payment QR of one signed PAGE order; never visits the QR or submits a payment. */
@Component
public class AlipayPageQrClient {
    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> CASHIER_HOSTS = Set.of(
            "openapi.alipay.com", "excashier.alipay.com", "cashier.alipay.com", "mapi.alipay.com");
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_QR_PATH = Pattern.compile("/upx[A-Za-z0-9_-]{8,128}");
    private final Supplier<Transport> sessions;

    public AlipayPageQrClient() {
        this(HttpTransport::new);
    }

    AlipayPageQrClient(Supplier<Transport> sessions) {
        this.sessions = sessions;
    }

    public String resolve(String signedPageUrl) {
        try {
            URI current = URI.create(signedPageUrl);
            ExpectedOrder expected = expectedOrder(current);
            long deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();
            try (Transport session = sessions.get()) {
              Set<URI> visited = new HashSet<>();
              for (int hop = 0; hop <= 5; hop++) {
                validateCashierUri(current);
                if (!visited.add(current)) throw unavailable("ALIPAY_PAGE_QR_REDIRECT_LOOP");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw unavailable("ALIPAY_PAGE_QR_TIMEOUT");
                Reply reply = session.get(current, Duration.ofNanos(remaining));
                if (reply.body().length > MAX_BODY_BYTES) throw unavailable("ALIPAY_PAGE_QR_TOO_LARGE");
                if (Set.of(301, 302, 303, 307, 308).contains(reply.status())) {
                    String location = reply.header("location");
                    if (location == null || location.isBlank() || hop == 5) {
                        throw unavailable("ALIPAY_PAGE_QR_REDIRECT_INVALID");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (reply.status() != 200) throw unavailable("ALIPAY_PAGE_QR_HTTP_ERROR");
                String html = decodeHtml(reply);
                return extractPaymentQr(html, expected);
              }
            }
            throw unavailable("ALIPAY_PAGE_QR_UNAVAILABLE");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable("ALIPAY_PAGE_QR_INTERRUPTED");
        } catch (GatewayException ex) {
            throw ex;
        } catch (Exception ex) {
            // Neither signed URLs nor upstream HTML/cookies are included in errors or logs.
            throw unavailable("ALIPAY_PAGE_QR_UNAVAILABLE");
        }
    }

    static void validateCashierUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !CASHIER_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.toString().length() > 32768) {
            throw unavailable("ALIPAY_PAGE_QR_ORIGIN_INVALID");
        }
        String path = uri.getPath();
        if (path == null || path.contains("..") || (!path.equals("/gateway.do") && !path.startsWith("/standard/"))) {
            throw unavailable("ALIPAY_PAGE_QR_PATH_INVALID");
        }
    }

    private static ExpectedOrder expectedOrder(URI initial) throws Exception {
        validateCashierUri(initial);
        if (!"openapi.alipay.com".equalsIgnoreCase(initial.getHost()) || !"/gateway.do".equals(initial.getPath())) {
            throw unavailable("ALIPAY_PAGE_QR_GATEWAY_INVALID");
        }
        Map<String, String> params = query(initial, StandardCharsets.UTF_8);
        Charset charset = Charset.forName(params.getOrDefault("charset", "UTF-8"));
        params = query(initial, charset);
        if (!"alipay.trade.page.pay".equals(params.get("method")) || params.getOrDefault("sign", "").isBlank()) {
            throw unavailable("ALIPAY_PAGE_QR_REQUEST_INVALID");
        }
        JsonNode biz = new ObjectMapper().readTree(params.getOrDefault("biz_content", "{}"));
        String order = biz.path("out_trade_no").asText();
        BigDecimal amount = new BigDecimal(biz.path("total_amount").asText());
        if (order.isBlank() || amount.signum() <= 0 || !"FAST_INSTANT_TRADE_PAY".equals(biz.path("product_code").asText())
                || !"4".equals(biz.path("qr_pay_mode").asText()) || !"PCWEB".equals(biz.path("integration_type").asText())) {
            throw unavailable("ALIPAY_PAGE_QR_REQUEST_INVALID");
        }
        return new ExpectedOrder(order, amount);
    }

    private static Map<String, String> query(URI uri, Charset charset) {
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) throw unavailable("ALIPAY_PAGE_QR_REQUEST_INVALID");
        for (String pair : uri.getRawQuery().split("&")) {
            String[] part = pair.split("=", 2);
            String key = URLDecoder.decode(part[0], charset);
            String value = URLDecoder.decode(part.length == 2 ? part[1] : "", charset);
            if (values.putIfAbsent(key, value) != null) throw unavailable("ALIPAY_PAGE_QR_REQUEST_INVALID");
        }
        return values;
    }

    static String decodeHtml(Reply reply) {
        String contentType = reply.header("content-type");
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw unavailable("ALIPAY_PAGE_QR_CONTENT_INVALID");
        }
        Charset charset = StandardCharsets.UTF_8;
        Matcher declared = CHARSET.matcher(contentType == null ? "" : contentType);
        if (!declared.find()) {
            String prefix = new String(reply.body(), 0, Math.min(reply.body().length, 4096), StandardCharsets.ISO_8859_1);
            declared = CHARSET.matcher(prefix);
        } else {
            return new String(reply.body(), Charset.forName(declared.group(1)));
        }
        if (declared.find()) charset = Charset.forName(declared.group(1));
        return new String(reply.body(), charset);
    }

    static String extractPaymentQr(String html, ExpectedOrder expected) throws Exception {
        List<String> qrValues = new ArrayList<>();
        List<String> orderValues = new ArrayList<>();
        List<String> amountValues = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            @Override
            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                if (tag != HTML.Tag.INPUT || !"hidden".equalsIgnoreCase(attr(attrs, HTML.Attribute.TYPE))) return;
                String name = attr(attrs, HTML.Attribute.NAME);
                String id = attr(attrs, HTML.Attribute.ID);
                String value = attr(attrs, HTML.Attribute.VALUE);
                if ("qrCode".equals(name) || "J_qrCode".equals(id)) qrValues.add(value);
                if ("out_trade_no".equals(name) || "outTradeNo".equals(name)) orderValues.add(value);
                if ("total_amount".equals(name) || "totalAmount".equals(name)) amountValues.add(value);
            }
        }, true);
        if (qrValues.isEmpty() || new HashSet<>(qrValues).size() != 1) {
            throw unavailable("ALIPAY_PAGE_QR_NOT_FOUND");
        }
        for (String order : orderValues) {
            if (!expected.outTradeNo().equals(order)) throw unavailable("ALIPAY_PAGE_QR_ORDER_MISMATCH");
        }
        for (String amount : amountValues) {
            if (expected.amount().compareTo(new BigDecimal(amount)) != 0) throw unavailable("ALIPAY_PAGE_QR_ORDER_MISMATCH");
        }
        String value = qrValues.getFirst();
        URI qr = URI.create(value);
        if (!"https".equals(qr.getScheme()) || !"qr.alipay.com".equals(qr.getHost()) || qr.getUserInfo() != null
                || (qr.getPort() != -1 && qr.getPort() != 443) || qr.getRawQuery() != null || qr.getRawFragment() != null
                || !PAYMENT_QR_PATH.matcher(qr.getRawPath()).matches()) {
            throw unavailable("ALIPAY_PAGE_QR_LINK_INVALID");
        }
        return value;
    }

    private static String attr(MutableAttributeSet attrs, HTML.Attribute attr) {
        Object value = attrs.getAttribute(attr);
        return value == null ? "" : value.toString();
    }

    private static GatewayException unavailable(String code) {
        return new GatewayException(code, "Official Alipay payment QR was not confirmed");
    }

    record ExpectedOrder(String outTradeNo, BigDecimal amount) {}

    record Reply(int status, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        Reply get(URI uri, Duration remaining) throws Exception;
        @Override default void close() {}
    }

    private static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();

        @Override public void close() { client.shutdownNow(); }

        @Override
        public Reply get(URI uri, Duration remaining) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(remaining)
                    .header("Accept", "text/html")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "PaymentGateway/1.0 AlipayPageQr")
                    .GET().build();
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new LimitedBody());
            try {
                HttpResponse<byte[]> response = future.get(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);
                return new Reply(response.statusCode(), response.headers().map(), response.body());
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
        }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(unavailable("ALIPAY_PAGE_QR_TOO_LARGE"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
