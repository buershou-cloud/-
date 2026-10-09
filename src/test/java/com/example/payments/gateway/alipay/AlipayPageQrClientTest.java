package com.example.payments.gateway.alipay;

import com.example.payments.gateway.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlipayPageQrClientTest {
    private static final String QR = "https://qr.alipay.com/upx0123456789TestOrder";
    private static final String CHECKOUT = "https://excashier.alipay.com/standard/qrCodePay.htm?payOrderId=test-order";
    private static final AlipayPageQrClient.ExpectedOrder EXPECTED =
            new AlipayPageQrClient.ExpectedOrder("ORIGINAL_PAGE_ORDER", new BigDecimal("1.00"));

    @Test void followsSameOrderOfficialRedirectsAndReturnsQrWithoutVisitingIt() throws Exception {
        List<URI> calls = new ArrayList<>();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, remaining) -> {
            calls.add(uri);
            assertThat(remaining).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(20));
            if (calls.size() == 1) return reply(302, "", Map.of("Location", List.of(CHECKOUT)));
            return reply(200, input("qrCode", QR) + input("out_trade_no", "ORIGINAL_PAGE_ORDER")
                    + input("total_amount", "1.0"), Map.of("Content-Type", List.of("text/html;charset=GBK")));
        });
        assertThat(client.resolve(signedUrl())).isEqualTo(QR);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getHost()).isEqualTo("openapi.alipay.com");
        assertThat(calls.get(1).toString()).isEqualTo(CHECKOUT);
        assertThat(calls).noneMatch(uri -> "qr.alipay.com".equals(uri.getHost()));
    }

    @Test void refusesNonAlipayInitialGatewayBeforeNetwork() {
        AtomicInteger sessions = new AtomicInteger();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> { sessions.incrementAndGet(); return (uri, time) -> null; });
        for (String url : List.of("https://127.0.0.1/gateway.do", "http://openapi.alipay.com/gateway.do",
                "https://openapi.alipay.com.attacker.example/gateway.do", "https://openapi.alipay.com:8888/gateway.do",
                "https://user@openapi.alipay.com/gateway.do", "https://openapi.alipay.com/private/gateway.do")) {
            assertThatThrownBy(() -> client.resolve(url)).isInstanceOf(GatewayException.class);
        }
        assertThat(sessions.get()).isZero();
    }

    @Test void neverFollowsMerchantReturnOrUnknownOriginAndDoesNotLeakSignedUrl() throws Exception {
        List<URI> calls = new ArrayList<>();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, time) -> {
            calls.add(uri);
            return reply(302, "", Map.of("location", List.of("https://merchant.example/return?privateToken=never-print-me")));
        });
        assertThatThrownBy(() -> client.resolve(signedUrl())).isInstanceOf(GatewayException.class)
                .hasMessageNotContaining("never-print-me").hasMessageNotContaining("TEST_SIGNATURE");
        assertThat(calls).hasSize(1);
    }

    @Test void rejectsWrongProductOrIntegrationBeforeNetwork() throws Exception {
        AlipayPageQrClient client = new AlipayPageQrClient(() -> { throw new AssertionError("must not send"); });
        for (String url : List.of(signedUrl().replace("alipay.trade.page.pay", "alipay.trade.wap.pay"),
                signedUrl().replace("FAST_INSTANT_TRADE_PAY", "FACE_TO_FACE_PAYMENT"),
                signedUrl().replace("PCWEB", "ALIAPP"), signedUrl() + "&method=alipay.trade.page.pay")) {
            assertThatThrownBy(() -> client.resolve(url)).isInstanceOf(GatewayException.class);
        }
    }

    @Test void detectsRedirectLoopWithoutRepeatingOrderRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String original = signedUrl();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, time) -> {
            calls.incrementAndGet();
            return reply(302, "", Map.of("location", List.of(original)));
        });
        assertThatThrownBy(() -> client.resolve(original)).isInstanceOf(GatewayException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void enforcesRedirectLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, time) ->
                reply(302, "", Map.of("location", List.of("https://excashier.alipay.com/standard/hop" + calls.incrementAndGet()))));
        assertThatThrownBy(() -> client.resolve(signedUrl())).isInstanceOf(GatewayException.class);
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test void doesNotRetryOnHttpFailureTimeoutOrMissingQr() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            int scenario = mode;
            AtomicInteger calls = new AtomicInteger();
            AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, time) -> {
                calls.incrementAndGet();
                if (scenario == 0) throw new TimeoutException("private signed URL");
                return reply(scenario == 1 ? 503 : 200, "<html>暂时没有付款码</html>", Map.of());
            });
            assertThatThrownBy(() -> client.resolve(signedUrl())).isInstanceOf(GatewayException.class)
                    .hasMessageNotContaining("private signed URL");
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test void createsAndClosesSeparateSessionForEveryOrder() throws Exception {
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        AlipayPageQrClient client = new AlipayPageQrClient(() -> {
            opened.incrementAndGet();
            return new AlipayPageQrClient.Transport() {
                public AlipayPageQrClient.Reply get(URI uri, Duration time) { return reply(200, input("qrCode", QR), Map.of()); }
                public void close() { closed.incrementAndGet(); }
            };
        });
        assertThat(client.resolve(signedUrl())).isEqualTo(QR);
        assertThat(client.resolve(signedUrl())).isEqualTo(QR);
        assertThat(opened.get()).isEqualTo(2);
        assertThat(closed.get()).isEqualTo(2);
    }

    @Test void readsOnlyNamedHiddenQrAndAllowsAttributeOrderAndQuoteVariation() throws Exception {
        assertThat(AlipayPageQrClient.extractPaymentQr(
                "<input value='" + QR + "' name='qrCode' type='hidden'>", EXPECTED)).isEqualTo(QR);
        assertThat(AlipayPageQrClient.extractPaymentQr(
                "<input type='hidden' id='J_qrCode' value='" + QR + "'>", EXPECTED)).isEqualTo(QR);
        assertThatThrownBy(() -> AlipayPageQrClient.extractPaymentQr(
                "<script>var qrCode='" + QR + "';</script><img src='" + QR + "'>", EXPECTED))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> AlipayPageQrClient.extractPaymentQr(
                "<input name='qrCode' value='" + QR + "'>", EXPECTED)).isInstanceOf(GatewayException.class);
    }

    @Test void refusesAmbiguousQrAndConflictingOriginalOrderOrAmount() {
        for (String html : List.of(input("qrCode", QR) + input("qrCode", "https://qr.alipay.com/upxDIFFERENT_ORDER"),
                input("qrCode", QR) + input("out_trade_no", "ANOTHER_ORDER"),
                input("qrCode", QR) + input("total_amount", "100.00"))) {
            assertThatThrownBy(() -> AlipayPageQrClient.extractPaymentQr(html, EXPECTED)).isInstanceOf(GatewayException.class);
        }
    }

    @Test void refusesGatewayLinksPersonalReceiveQrAndMaliciousPaymentLinks() {
        for (String url : List.of("javascript:alert(1)", "https://qr.alipay.com/fkxPersonalReceive",
                "https://openapi.alipay.com/gateway.do", "https://qr.alipay.com.attacker.example/upx12345678",
                "https://user@qr.alipay.com/upx12345678", "https://qr.alipay.com:8888/upx12345678",
                QR + "#other", QR + "?other=1", "https://qr.alipay.com/%75px12345678")) {
            assertThatThrownBy(() -> AlipayPageQrClient.extractPaymentQr(input("qrCode", url), EXPECTED))
                    .isInstanceOf(GatewayException.class);
        }
    }

    @Test void supportsGbkPageAndMetaCharsetWithoutExecutingHtml() {
        String html = "<meta charset='gbk'><div>支付宝付款码</div>" + input("qrCode", QR);
        AlipayPageQrClient.Reply reply = new AlipayPageQrClient.Reply(200, Map.of(), html.getBytes(Charset.forName("GBK")));
        assertThat(AlipayPageQrClient.decodeHtml(reply)).isEqualTo(html);
        assertThatThrownBy(() -> AlipayPageQrClient.decodeHtml(reply(200, "{}", Map.of("content-type", List.of("application/json")))))
                .isInstanceOf(GatewayException.class);
    }

    @Test void rejectsOversizedHtml() throws Exception {
        AlipayPageQrClient client = new AlipayPageQrClient(() -> (uri, time) ->
                new AlipayPageQrClient.Reply(200, Map.of(), new byte[AlipayPageQrClient.MAX_BODY_BYTES + 1]));
        assertThatThrownBy(() -> client.resolve(signedUrl())).isInstanceOf(GatewayException.class);
    }

    @Test void cancelsBodySubscriptionBeforeAccumulatingMoreThanLimit() {
        AlipayPageQrClient.LimitedBody body = new AlipayPageQrClient.LimitedBody();
        AtomicInteger cancelled = new AtomicInteger();
        body.onSubscribe(new Flow.Subscription() {
            public void request(long count) {}
            public void cancel() { cancelled.incrementAndGet(); }
        });
        body.onNext(List.of(ByteBuffer.wrap(new byte[AlipayPageQrClient.MAX_BODY_BYTES]), ByteBuffer.wrap(new byte[1])));
        assertThat(cancelled.get()).isEqualTo(1);
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    private static String signedUrl() throws Exception {
        String biz = new ObjectMapper().writeValueAsString(Map.of("out_trade_no", "ORIGINAL_PAGE_ORDER", "subject", "test",
                "total_amount", "1.00", "product_code", "FAST_INSTANT_TRADE_PAY", "qr_pay_mode", "4", "integration_type", "PCWEB"));
        return "https://openapi.alipay.com/gateway.do?charset=UTF-8&method=alipay.trade.page.pay&sign=TEST_SIGNATURE&biz_content="
                + URLEncoder.encode(biz, StandardCharsets.UTF_8);
    }

    private static String input(String name, String value) {
        return "<input name=\"" + name + "\" type=\"hidden\" value=\"" + value + "\">";
    }

    private static AlipayPageQrClient.Reply reply(int status, String body, Map<String, List<String>> headers) {
        return new AlipayPageQrClient.Reply(status, headers, body.getBytes(StandardCharsets.UTF_8));
    }
}
