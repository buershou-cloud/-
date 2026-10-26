package com.example.payments.merchant.api;

import com.example.payments.merchant.DemoMerchantService;
import com.example.payments.merchant.DemoMerchantView;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class MerchantNotifyService {
    private static final Logger log = LoggerFactory.getLogger(MerchantNotifyService.class);
    private static final long[] RETRY_SECONDS = {5, 30, 60, 300, 900, 3600};
    private final DemoOrderService orderService;
    private final DemoMerchantService merchantService;
    private final MerchantSignatureService signatureService;
    private final ObjectMapper objectMapper;
    private static final Object[] CREATION_LOCKS = java.util.stream.IntStream.range(0, 64)
            .mapToObj(ignored -> new Object()).toArray();
    private final MerchantCallbackTransport transport;
    private final Path outboxDirectory;
    private final Clock clock;
    private final Executor executor;
    private final Set<Path> scheduled = ConcurrentHashMap.newKeySet();

    @Autowired
    public MerchantNotifyService(DemoOrderService orderService, DemoMerchantService merchantService,
            MerchantSignatureService signatureService, ObjectMapper objectMapper,
            @Value("${payment.merchant.notify-outbox-directory:data/merchant-notifications}") String outboxDirectory) {
        this(orderService, merchantService, signatureService, objectMapper, Path.of(outboxDirectory),
                new SafeMerchantCallbackClient(), Clock.systemUTC(), callbackExecutor());
    }

    MerchantNotifyService(DemoOrderService orderService, DemoMerchantService merchantService,
            MerchantSignatureService signatureService, ObjectMapper objectMapper, Path outboxDirectory,
            MerchantCallbackTransport transport, Clock clock, Executor executor) {
        this.orderService = orderService;
        this.merchantService = merchantService;
        this.signatureService = signatureService;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.outboxDirectory = outboxDirectory.toAbsolutePath().normalize();
        this.clock = clock;
        this.executor = executor;
        try {
            Files.createDirectories(this.outboxDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot initialize merchant notification outbox", ex);
        }
    }

    /** Persist before acknowledging upstream; network failures are retried independently. */
    public void notifyPayment(DemoOrderView order, String tradeStatus) {
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
        URI callback = SafeMerchantCallbackClient.callbackUri(target.get().notifyUrl());
        String eventId = eventId(order.merchantId(), order.outTradeNo(), order.status().name());
        Path file = outboxDirectory.resolve(eventId + ".json");
        // An existing file is atomically complete. Never wait on its in-flight HTTP delivery lock.
        if (!Files.exists(file)) {
            synchronized (CREATION_LOCKS[(eventId.hashCode() & Integer.MAX_VALUE) % CREATION_LOCKS.length]) {
                if (!Files.exists(file)) {
                    try (FileChannel lockChannel = lockChannel(file); FileLock ignored = lockChannel.lock()) {
                        if (!Files.exists(file)) {
                            Delivery delivery = new Delivery(eventId, order.merchantId(), order.outTradeNo(), callback.toString(),
                                    payload(order, tradeStatus), 0, clock.millis(), false, null);
                            write(file, delivery);
                        }
                    } catch (IOException ex) {
                        throw new IllegalStateException("Cannot persist merchant notification for " + order.outTradeNo(), ex);
                    }
                }
            }
        }
        enqueue(file);
    }

    @Scheduled(initialDelayString = "${payment.merchant.notify-retry-initial-delay-ms:5000}",
            fixedDelayString = "${payment.merchant.notify-retry-delay-ms:5000}")
    public void retryPending() {
        try (var files = Files.list(outboxDirectory)) {
            int attempted = 0;
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (attempted >= 20 || Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    Delivery delivery = read(file);
                    if (!delivery.delivered() && delivery.nextAttemptAt() <= clock.millis() && enqueue(file)) {
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

    private boolean enqueue(Path file) {
        if (!scheduled.add(file)) return false;
        try {
            executor.execute(() -> {
                try (FileChannel lockChannel = lockChannel(file); FileLock lock = lockChannel.tryLock()) {
                    if (lock != null) deliverIfDue(file, read(file));
                } catch (OverlappingFileLockException ignored) {
                    // Another instance in this JVM is delivering; its durable state will govern retries.
                } catch (IOException | RuntimeException ex) {
                    log.error("Cannot process merchant notification outbox entry {}", file.getFileName(), ex);
                } finally {
                    scheduled.remove(file);
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            scheduled.remove(file);
            // The durable record remains available to the next scheduled retry; never send on the caller thread.
            return false;
        }
    }

    private static ExecutorService callbackExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), task -> {
            Thread thread = new Thread(task, "merchant-callback-delivery");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void close() throws IOException {
        if (executor instanceof ExecutorService service) service.shutdownNow();
        transport.close();
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
            MerchantCallbackTransport.Result response = transport.post(
                    SafeMerchantCallbackClient.callbackUri(delivery.notifyUrl()), form(payload));
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
            // Readers use the complete file without taking a delivery lock. Require atomic publication.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Map<String, Object> payload(DemoOrderView order, String tradeStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", order.merchantId());
        payload.put("outTradeNo", order.outTradeNo());
        String parentOutTradeNo = orderService.captureParentOutTradeNo(order.outTradeNo());
        if (parentOutTradeNo != null && !parentOutTradeNo.isBlank()) {
            payload.put("parentOutTradeNo", parentOutTradeNo);
        }
        payload.put("tradeNo", order.tradeNo());
        payload.put("channelId", order.channelId());
        payload.put("productName", order.productName());
        payload.put("totalAmount", order.amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("tradeStatus", tradeStatus);
        payload.put("status", order.status().name());
        payload.put("notifyTime", Instant.now(clock).toString());
        return payload;
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
