package com.example.payments.merchant.api;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MerchantNotifyService {
    private static final Logger log = LoggerFactory.getLogger(MerchantNotifyService.class);
    private static final long[] RETRY_SECONDS = {5, 30, 60, 300, 900, 3600};
    private final DemoOrderService orderService;
    private final DemoMerchantService merchantService;
    private final MerchantSignatureService signatureService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Path outboxDirectory;
    private final Clock clock;

    @Autowired
    public MerchantNotifyService(DemoOrderService orderService, DemoMerchantService merchantService,
            MerchantSignatureService signatureService, ObjectMapper objectMapper,
            @Value("${payment.merchant.notify-outbox-directory:data/merchant-notifications}") String outboxDirectory) {
        this(orderService, merchantService, signatureService, objectMapper, Path.of(outboxDirectory),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.systemUTC());
    }

    MerchantNotifyService(DemoOrderService orderService, DemoMerchantService merchantService,
            MerchantSignatureService signatureService, ObjectMapper objectMapper, Path outboxDirectory,
            HttpClient httpClient, Clock clock) {
        this.orderService = orderService;
        this.merchantService = merchantService;
        this.signatureService = signatureService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.outboxDirectory = outboxDirectory.toAbsolutePath().normalize();
        this.clock = clock;
        try {
            Files.createDirectories(this.outboxDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot initialize merchant notification outbox", ex);
        }
    }

    /** Persist before acknowledging upstream; network failures are retried independently. */
    public synchronized void notifyPayment(DemoOrderView order, String tradeStatus) {
        if (order == null || order.status() == null || order.status() == DemoOrderStatus.UNPAID) {
            return;
        }
        Optional<DemoOrderService.MerchantNotifyTarget> target = orderService.merchantNotifyTarget(order.outTradeNo());
        if (target.isEmpty()) {
            return;
        }
        if (!order.merchantId().equals(target.get().merchantId())) {
            throw new IllegalStateException("Merchant notification target does not own the order");
        }
        URI callback = callbackUri(target.get().notifyUrl());
        String eventId = eventId(order.merchantId(), order.outTradeNo(), order.status().name());
        Path file = outboxDirectory.resolve(eventId + ".json");
        try (FileChannel lockChannel = lockChannel(file); FileLock ignored = lockChannel.lock()) {
            Delivery delivery;
            if (Files.exists(file)) {
                delivery = read(file);
            } else {
                delivery = new Delivery(eventId, order.merchantId(), order.outTradeNo(), callback.toString(),
                        payload(order, tradeStatus), 0, clock.millis(), false, null);
                write(file, delivery);
            }
            deliverIfDue(file, delivery);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot persist merchant notification for " + order.outTradeNo(), ex);
        }
    }

    @Scheduled(initialDelayString = "${payment.merchant.notify-retry-initial-delay-ms:5000}",
            fixedDelayString = "${payment.merchant.notify-retry-delay-ms:5000}")
    public synchronized void retryPending() {
        try (var files = Files.list(outboxDirectory)) {
            int attempted = 0;
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (attempted >= 20 || Thread.currentThread().isInterrupted()) {
                    break;
                }
                try (FileChannel lockChannel = lockChannel(file); FileLock ignored = lockChannel.lock()) {
                    if (deliverIfDue(file, read(file))) {
                        attempted++;
                    }
                } catch (IOException | RuntimeException ex) {
                    log.error("Cannot process merchant notification outbox entry {}", file.getFileName(), ex);
                }
            }
        } catch (IOException ex) {
            log.error("Cannot read merchant notification outbox", ex);
        }
    }

    private boolean deliverIfDue(Path file, Delivery delivery) throws IOException {
        if (delivery.delivered() || delivery.nextAttemptAt() > clock.millis()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>(delivery.payload());
        boolean success = false;
        String failure = null;
        try {
            DemoMerchantView merchant = merchantService.detail(delivery.merchantId());
            String signType = signatureService.defaultSignType(merchant);
            payload.remove("sign");
            payload.put("signType", signType);
            payload.put("sign", signatureService.signForMerchant(merchant, signType, payload));
            HttpRequest request = HttpRequest.newBuilder(callbackUri(delivery.notifyUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            success = response.statusCode() >= 200 && response.statusCode() < 300
                    && response.body() != null && "success".equalsIgnoreCase(response.body().trim());
            if (!success) {
                failure = "HTTP " + response.statusCode() + " without success acknowledgement";
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failure = "Interrupted";
        } catch (IOException | RuntimeException ex) {
            failure = ex.getClass().getSimpleName();
        }
        int attempts = delivery.attempts() + 1;
        long nextAttempt = success ? 0 : clock.millis() + RETRY_SECONDS[Math.min(attempts - 1, RETRY_SECONDS.length - 1)] * 1000;
        write(file, new Delivery(delivery.eventId(), delivery.merchantId(), delivery.outTradeNo(), delivery.notifyUrl(),
                payload, attempts, nextAttempt, success, failure));
        if (!success) {
            log.warn("Merchant notification awaiting retry: outTradeNo={}, attempt={}, reason={}", delivery.outTradeNo(), attempts, failure);
        }
        return true;
    }

    private FileChannel lockChannel(Path file) throws IOException {
        return FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private Delivery read(Path file) throws IOException {
        return objectMapper.readValue(file.toFile(), Delivery.class);
    }

    private void write(Path file, Delivery delivery) throws IOException {
        Path temporary = Files.createTempFile(outboxDirectory, ".notify-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), delivery);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> payload(DemoOrderView order, String tradeStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", order.merchantId());
        payload.put("outTradeNo", order.outTradeNo());
        payload.put("tradeNo", order.tradeNo());
        payload.put("channelId", order.channelId());
        payload.put("productName", order.productName());
        payload.put("totalAmount", order.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("tradeStatus", tradeStatus);
        payload.put("status", order.status().name());
        payload.put("notifyTime", Instant.now(clock).toString());
        return payload;
    }

    private static URI callbackUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Merchant notifyUrl must be an absolute HTTP(S) URL", ex);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Merchant notifyUrl must be an absolute HTTP(S) URL without user information or fragment");
        }
        return uri;
    }

    private static String eventId(String merchantId, String outTradeNo, String status) {
        try {
            String input = merchantId.length() + ":" + merchantId + outTradeNo.length() + ":" + outTradeNo + ":" + status;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String form(Map<String, Object> payload) {
        return payload.entrySet().stream().filter(entry -> entry.getValue() != null)
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().toString()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Delivery(String eventId, String merchantId, String outTradeNo, String notifyUrl,
                    Map<String, Object> payload, int attempts, long nextAttemptAt, boolean delivered, String lastError) { }
}
