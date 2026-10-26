package com.example.payments.merchant.api;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class SafeMerchantCallbackClientTest {
    private static final DnsResolver LOCAL_TEST_RESOLVER = new DnsResolver() {
        @Override public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            return new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        }
        @Override public String resolveCanonicalHostname(String host) { return host; }
    };

    @Test
    void sendsUnchangedFormToResolvedAddressWithOriginalHostAndNeverFollowsRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> hostHeader = new AtomicReference<>();
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/notify", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).startsWith("application/x-www-form-urlencoded");
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = " success\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/trap");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/trap", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.start();
        try (var client = new SafeMerchantCallbackClient(LOCAL_TEST_RESOLVER)) {
            String origin = "http://merchant.test:" + server.getAddress().getPort();
            var result = client.post(URI.create(origin + "/notify"), "merchantId=M1&sign=unchanged%2Bsign");
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).isEqualTo(" success\n");
            assertThat(requestBody).hasValue("merchantId=M1&sign=unchanged%2Bsign");
            assertThat(hostHeader).hasValue("merchant.test:" + server.getAddress().getPort());
            assertThat(client.post(URI.create(origin + "/redirect"), "x=1").statusCode()).isEqualTo(302);
            assertThat(redirected).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void limitsDeclaredAndChunkedResponseBodies(boolean declaredLength) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            byte[] body = new byte[SafeMerchantCallbackClient.MAX_RESPONSE_BYTES + 100];
            exchange.sendResponseHeaders(200, declaredLength ? body.length : 0);
            try { exchange.getResponseBody().write(body); }
            catch (IOException ignored) { /* Client closes oversized responses immediately. */ }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = new SafeMerchantCallbackClient(LOCAL_TEST_RESOLVER)) {
            URI callback = URI.create("http://merchant.test:" + server.getAddress().getPort() + "/notify");
            assertThatThrownBy(() -> client.post(callback, "x=1")).isInstanceOf(IOException.class)
                    .hasMessageContaining("size limit");
        } finally { server.stop(0); }
    }

    @Test
    void productionResolverRejectsPrivateDnsBeforeOpeningSocket() throws Exception {
        var lookups = new AtomicInteger();
        var resolver = new PublicCallbackDnsResolver(host -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        });
        try (var client = new SafeMerchantCallbackClient(resolver)) {
            assertThatThrownBy(() -> client.post(URI.create("http://merchant.test:8080/notify"), "x=1"))
                    .isInstanceOf(IOException.class).hasMessageContaining("not public");
            assertThat(lookups).hasValue(1);
        }
    }

    @Test
    void totalDeadlineStopsSlowStreamingAcknowledgement() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var finish = new CountDownLatch(1);
        server.createContext("/notify", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                // Each byte arrives before the read timeout, so only a whole-request deadline bounds this response.
                while (!finish.await(300, TimeUnit.MILLISECONDS)) {
                    exchange.getResponseBody().write('s');
                    exchange.getResponseBody().flush();
                }
            } catch (IOException ignored) {
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try (var client = new SafeMerchantCallbackClient(LOCAL_TEST_RESOLVER)) {
            URI callback = URI.create("http://merchant.test:" + server.getAddress().getPort() + "/notify");
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(8), () ->
                    assertThatThrownBy(() -> client.post(callback, "x=1")).isInstanceOf(IOException.class));
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/notify", "http://2130706433/notify", "http://[::1]/notify",
            "http://169.254.169.254/latest/meta-data", "http://[::ffff:127.0.0.1]/notify"})
    void rejectsPrivateIpLiteralsEvenIfHttpLibraryWouldSkipDns(String url) throws Exception {
        try (var client = new SafeMerchantCallbackClient()) {
            assertThatThrownBy(() -> client.post(URI.create(url), "x=1")).isInstanceOf(IOException.class);
        }
    }
}
