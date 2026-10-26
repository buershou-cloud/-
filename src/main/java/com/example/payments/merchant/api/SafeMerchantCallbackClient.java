package com.example.payments.merchant.api;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Direct connections only, with public DNS pinned by the connection operator and bounded acknowledgements. */
final class SafeMerchantCallbackClient implements MerchantCallbackTransport {
    static final int MAX_RESPONSE_BYTES = 4096;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "merchant-callback-deadline");
        thread.setDaemon(true);
        return thread;
    });

    SafeMerchantCallbackClient() {
        this(new PublicCallbackDnsResolver());
    }

    // Package-private seam for local HTTP transport tests; production always uses the public-only resolver above.
    SafeMerchantCallbackClient(DnsResolver resolver) {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom().setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
                .setMaxConnTotal(4).setMaxConnPerRoute(2)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(5)).build())
                .build();
        client = HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(1)).setResponseTimeout(Timeout.ofSeconds(5)).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                // Do not opt into system proxies. New connections always pass through our resolver.
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }

    @Override
    @SuppressWarnings("deprecation") // Raw response ownership is needed to abort oversized bodies without draining them.
    public Result post(URI uri, String form) throws IOException {
        URI validated = callbackUri(uri.toString());
        String host = validated.getHost();
        // HttpClient can bypass its DNS resolver for IP literals; apply the identical policy here.
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            PublicCallbackDnsResolver.requirePublic(InetAddress.getByName(host));
        }
        HttpPost request = new HttpPost(validated);
        request.setEntity(new StringEntity(form, ContentType.create("application/x-www-form-urlencoded", StandardCharsets.UTF_8)));
        request.setHeader("Connection", "close");
        var deadline = deadlines.schedule(() -> { request.cancel(); }, 5, TimeUnit.SECONDS);
        try {
            var response = client.execute(request);
            try {
                int status = response.getCode();
                if (status < 200 || status >= 300) return new Result(status, "");
                var entity = response.getEntity();
                if (entity == null) return new Result(status, "");
                if (entity.getContentLength() > MAX_RESPONSE_BYTES) throw new IOException("Callback acknowledgement exceeds size limit");
                byte[] body = entity.getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) throw new IOException("Callback acknowledgement exceeds size limit");
                return new Result(status, new String(body, StandardCharsets.UTF_8));
            } finally {
                response.close(CloseMode.IMMEDIATE);
            }
        } finally {
            deadline.cancel(false);
        }
    }

    static URI callbackUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Merchant notifyUrl must be an absolute HTTP(S) URL", ex);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Merchant notifyUrl must be an absolute HTTP(S) URL without user information or fragment");
        }
        return uri;
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }
}
