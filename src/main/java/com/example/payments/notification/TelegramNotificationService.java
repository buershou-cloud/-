package com.example.payments.notification;

import com.example.payments.order.ConfirmedReceiptSink;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

@Service
public class TelegramNotificationService implements ConfirmedReceiptSink {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);
    private static final long SEND_INTERVAL_MS = 3000;
    private static final long[] BACKOFF_SECONDS = {5, 30, 60, 300, 900, 3600};
    private final ObjectMapper mapper;
    private final Path directory;
    private final TelegramTransport transport;
    private final Clock clock;
    private final ZoneId timeZone;
    private final TelegramReceiptLedger receipts;
    private volatile Settings current = new Settings(false, "", "");
    private volatile String lastError = "";

    @Autowired
    public TelegramNotificationService(ObjectMapper mapper,
            @Value("${payment.telegram.directory:data/telegram-notifications}") String directory,
            @Value("${payment.telegram.time-zone:Asia/Bangkok}") String timeZone) {
        this(mapper, configuredPath(directory), new TelegramBotClient(mapper), Clock.systemUTC(), configuredZone(timeZone));
    }

    TelegramNotificationService(ObjectMapper mapper, Path directory, TelegramTransport transport, Clock clock, ZoneId timeZone) {
        this.mapper = mapper;
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
        this.transport = transport;
        this.clock = clock;
        this.timeZone = timeZone;
        this.receipts = new TelegramReceiptLedger(mapper, this.directory, clock, timeZone);
        try {
            current = loadSettings();
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_CONFIG_READ_FAILED", "通知配置暂不可读，请重新保存配置");
        }
    }

    public SettingsView settings() {
        try {
            Settings settings = loadSettings();
            current = settings;
            return view(settings);
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_CONFIG_READ_FAILED", "通知配置暂不可读，请重新保存配置");
            return new SettingsView(false, false, "", lastError);
        }
    }

    public SettingsView saveSettings(SettingsUpdate update) {
        if (update == null || update.enabled() == null) {
            throw new SettingsException("TELEGRAM_SETTINGS_INVALID", "请明确是否启用通知", false);
        }
        String chatId = update.chatId() == null ? "" : update.chatId().trim();
        validateChatId(chatId, update.enabled());
        String replacement = update.botToken() == null ? "" : update.botToken().trim();
        if (!replacement.isEmpty()) validateToken(replacement);
        try {
            ensureDirectory();
            return NotificationFiles.locked(directory.resolve("settings.lock"), true, () -> {
                Settings existing;
                try {
                    existing = loadSettings();
                } catch (IOException | RuntimeException ex) {
                    // A valid replacement can repair a corrupt file; blank tokens preserve the last valid configuration.
                    existing = current;
                }
                String token = replacement.isEmpty() ? existing.botToken() : replacement;
                if (update.enabled() && token.isEmpty()) {
                    throw new SettingsException("TELEGRAM_TOKEN_REQUIRED", "启用通知前请填写机器人 Token", false);
                }
                Settings next = new Settings(update.enabled(), token, chatId);
                NotificationFiles.write(mapper, directory.resolve("settings.json"), next);
                current = next;
                lastError = "";
                return view(next);
            });
        } catch (IOException ex) {
            failure("TELEGRAM_CONFIG_WRITE_FAILED", "通知配置保存失败，请检查目录权限");
            throw new SettingsException("TELEGRAM_CONFIG_WRITE_FAILED", lastError, true);
        } catch (RuntimeException ex) {
            if (ex instanceof SettingsException settingsException) throw settingsException;
            failure("TELEGRAM_CONFIG_WRITE_FAILED", "通知配置保存失败，请检查目录权限");
            throw new SettingsException("TELEGRAM_CONFIG_WRITE_FAILED", lastError, true);
        }
    }

    @Override
    public void enqueueConfirmedReceipt(DemoOrderView order) {
        if (order == null || order.status() != DemoOrderStatus.COMPLETED || order.preAuthorization()
                || order.supplemented() || order.amount() == null || order.amount().signum() <= 0
                || order.outTradeNo() == null || order.outTradeNo().isBlank()) return;
        try {
            Settings settings;
            try {
                settings = loadSettings();
            } catch (IOException | RuntimeException ex) {
                failure("TELEGRAM_CONFIG_READ_FAILED", "通知配置暂不可读，请重新保存配置");
                settings = new Settings(false, "", "");
            }
            String eventId = digest("receipt:" + order.outTradeNo());
            receipts.record(eventId, order.amount(), settings.enabled() ? settings.chatId() : null,
                    settings.enabled() ? digest(settings.botToken()) : null,
                    (confirmedAt, totals) -> receiptMessage(order, confirmedAt, totals));
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_ENQUEUE_FAILED", "收款提醒暂未写入，请检查通知目录或配置");
        }
    }

    @Scheduled(initialDelayString = "${payment.telegram.initial-delay-ms:5000}",
            fixedDelayString = "${payment.telegram.dispatch-delay-ms:1000}")
    public void sendPending() {
        try {
            try {
                receipts.recover();
            } catch (IOException | RuntimeException ex) {
                failure("TELEGRAM_RECEIPT_RECOVERY_FAILED", "部分收款提醒暂未恢复，请检查通知目录");
            }
            Settings settings = loadSettings();
            if (!settings.enabled()) return;
            ensureDirectory();
            NotificationFiles.locked(directory.resolve("dispatch.lock"), false, () -> {
                if (nextDispatchAt() > clock.millis()) return false;
                Path outbox = directory.resolve("outbox");
                if (!Files.isDirectory(outbox)) return false;
                List<Path> files;
                try (var stream = Files.list(outbox)) {
                    files = stream.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                            .sorted().toList();
                }
                for (Path file : files) {
                    if (Thread.currentThread().isInterrupted()) break;
                    Delivery delivery;
                    try {
                        delivery = NotificationFiles.read(mapper, file, Delivery.class);
                        if (delivery == null || delivery.eventId() == null || delivery.chatId() == null
                                || delivery.tokenFingerprint() == null || delivery.text() == null
                                || delivery.text().length() > 4096 || delivery.attempts() < 0
                                || !file.getFileName().toString().equals(delivery.eventId() + ".json")) {
                            throw new IOException("Invalid notification record");
                        }
                    } catch (IOException | RuntimeException ex) {
                        failure("TELEGRAM_OUTBOX_READ_FAILED", "有通知记录暂不可读，请检查通知目录");
                        continue;
                    }
                    if (delivery.delivered() || delivery.nextAttemptAt() > clock.millis()) continue;
                    Settings active = loadSettings();
                    if (!active.enabled()) break;
                    if (!active.chatId().equals(delivery.chatId()) || !digest(active.botToken()).equals(delivery.tokenFingerprint())) {
                        lastError = "目标配置已变更，旧通知保留待核对";
                        continue;
                    }
                    writeDispatchAt(clock.millis() + SEND_INTERVAL_MS);
                    TelegramTransport.Result result = safeSend(active, delivery.text());
                    int attempts = delivery.attempts() == Integer.MAX_VALUE ? Integer.MAX_VALUE : delivery.attempts() + 1;
                    long retrySeconds = Math.max(BACKOFF_SECONDS[Math.min(Math.max(0, attempts - 1), BACKOFF_SECONDS.length - 1)], result.retryAfterSeconds());
                    long retryAt = clock.millis() + retrySeconds * 1000;
                    NotificationFiles.write(mapper, file, new Delivery(delivery.eventId(), delivery.chatId(), delivery.tokenFingerprint(),
                            delivery.text(), attempts, result.success() ? 0 : retryAt, result.success(), result.messageId(),
                            result.success() ? "" : result.code()));
                    if (result.retryAfterSeconds() > 0) writeDispatchAt(clock.millis() + result.retryAfterSeconds() * 1000);
                    lastError = result.success() ? "" : result.message();
                    return true; // At most one send per pass, shared three-second minimum across processes.
                }
                return false;
            });
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_DISPATCH_FAILED", "通知发送队列暂不可用，请检查配置和目录");
        }
    }

    public TestResult sendTest() {
        try {
            Settings settings = loadSettings();
            if (settings.botToken().isEmpty() || settings.chatId().isEmpty()) {
                return new TestResult(false, "TELEGRAM_NOT_CONFIGURED", "请先保存机器人 Token 和群 ID");
            }
            ensureDirectory();
            TestResult result = NotificationFiles.locked(directory.resolve("dispatch.lock"), false, () -> {
                if (nextDispatchAt() > clock.millis()) {
                    return new TestResult(false, "TELEGRAM_RATE_LIMITED", "请稍后再发送测试消息");
                }
                writeDispatchAt(clock.millis() + SEND_INTERVAL_MS);
                TelegramTransport.Result sent = safeSend(settings, "收款通知测试\n机器人配置已连接\n时间：" + confirmationTime());
                if (sent.retryAfterSeconds() > 0) writeDispatchAt(clock.millis() + sent.retryAfterSeconds() * 1000);
                lastError = sent.success() ? "" : sent.message();
                return new TestResult(sent.success(), sent.code(), sent.message());
            });
            return result == null ? new TestResult(false, "TELEGRAM_BUSY", "正在发送通知，请稍后再试") : result;
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_TEST_FAILED", "测试消息暂未确认，请检查配置和通知目录");
            return new TestResult(false, "TELEGRAM_TEST_FAILED", lastError);
        }
    }

    private TelegramTransport.Result safeSend(Settings settings, String text) {
        try {
            TelegramTransport.Result result = transport.send(settings.botToken(), settings.chatId(), text);
            if (result != null && (!result.success() || (result.messageId() != null && result.messageId() > 0))) return result;
        } catch (RuntimeException ex) {
            // Custom transports must not leak token-bearing request URLs into payment errors or logs.
        }
        return TelegramTransport.Result.failure("TELEGRAM_NETWORK_ERROR", "无法连接 Telegram，稍后重试", 0);
    }

    private Settings loadSettings() throws IOException {
        if (directory == null) throw new IOException("Notification directory unavailable");
        Path file = directory.resolve("settings.json");
        if (!Files.exists(file)) return new Settings(false, "", "");
        Settings settings = NotificationFiles.read(mapper, file, Settings.class);
        if (settings == null || settings.botToken() == null || settings.chatId() == null) throw new IOException("Invalid settings");
        if (!settings.botToken().isEmpty()) validateToken(settings.botToken());
        validateChatId(settings.chatId(), settings.enabled());
        if (settings.enabled() && settings.botToken().isEmpty()) throw new IOException("Incomplete settings");
        return settings;
    }

    private long nextDispatchAt() throws IOException {
        Path file = directory.resolve("dispatch.json");
        return Files.exists(file) ? NotificationFiles.read(mapper, file, Dispatch.class).nextAt() : 0;
    }

    private void writeDispatchAt(long nextAt) throws IOException {
        NotificationFiles.write(mapper, directory.resolve("dispatch.json"), new Dispatch(nextAt));
    }

    private void ensureDirectory() throws IOException { NotificationFiles.directory(directory); }

    private SettingsView view(Settings settings) {
        return new SettingsView(settings.enabled(), !settings.botToken().isEmpty(), settings.chatId(), lastError);
    }

    private String receiptMessage(DemoOrderView order, Instant confirmedAt, TelegramReceiptLedger.Totals totals) {
        return "收款成功\n金额：¥" + order.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "\n商户：" + shortText(order.merchantName(), 80) + "（" + shortText(order.merchantId(), 64) + "）"
                + "\n通道：" + shortText(order.channelId(), 80)
                + "\n订单号：" + shortText(order.outTradeNo(), 128)
                + "\n平台单号：" + shortText(order.tradeNo(), 128)
                + "\n确认时间：" + confirmationTime(confirmedAt)
                + "\n今日（" + confirmedAt.atZone(timeZone).toLocalDate() + "）已收 " + totals.count()
                + " 笔 / 合计 ¥" + totals.amount().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String confirmationTime() {
        return confirmationTime(Instant.now(clock));
    }

    private String confirmationTime(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(timeZone).format(instant);
    }

    @Override
    public void rememberPendingCapture(DemoOrderView frozenOrder, String captureOutTradeNo, BigDecimal amount) {
        if (frozenOrder == null || frozenOrder.status() != DemoOrderStatus.FROZEN || !frozenOrder.preAuthorization()
                || frozenOrder.supplemented() || frozenOrder.amount() == null || frozenOrder.amount().signum() <= 0
                || !captureIdentityValid(frozenOrder.outTradeNo(), captureOutTradeNo, frozenOrder.channelId(), amount)
                || amount.compareTo(frozenOrder.amount()) > 0) return;
        try {
            ensureDirectory();
            Path captures = directory.resolve("captures");
            String id = captureId(frozenOrder.outTradeNo(), captureOutTradeNo);
            NotificationFiles.locked(captures.resolve("captures.lock"), true, () -> {
                Path file = captures.resolve(id + ".json");
                Capture capture = new Capture(frozenOrder.outTradeNo(), captureOutTradeNo, frozenOrder.channelId(), amount);
                if (Files.exists(file)) {
                    if (!sameCapture(NotificationFiles.read(mapper, file, Capture.class), capture)) throw new IOException("Capture identity conflict");
                } else NotificationFiles.write(mapper, file, capture);
                return true;
            });
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_CAPTURE_WRITE_FAILED", "收款确认标记暂未写入，请检查通知目录");
        }
    }

    @Override
    public boolean hasPendingCapture(String parentOutTradeNo, String captureOutTradeNo, String channelId, BigDecimal amount) {
        if (!captureIdentityValid(parentOutTradeNo, captureOutTradeNo, channelId, amount)) return false;
        try {
            if (directory == null) return false;
            Path file = directory.resolve("captures").resolve(captureId(parentOutTradeNo, captureOutTradeNo) + ".json");
            return Files.exists(file) && sameCapture(NotificationFiles.read(mapper, file, Capture.class),
                    new Capture(parentOutTradeNo, captureOutTradeNo, channelId, amount));
        } catch (IOException | RuntimeException ex) {
            failure("TELEGRAM_CAPTURE_READ_FAILED", "收款确认标记暂不可读，请检查通知目录");
            return false;
        }
    }

    private static boolean captureIdentityValid(String parent, String child, String channel, BigDecimal amount) {
        return parent != null && !parent.isBlank() && child != null && !child.isBlank()
                && channel != null && !channel.isBlank() && amount != null && amount.signum() > 0;
    }

    private static String captureId(String parent, String child) { return digest("capture:" + parent.length() + ":" + parent + child); }

    private static boolean sameCapture(Capture stored, Capture expected) {
        return stored != null && expected.parentOutTradeNo().equals(stored.parentOutTradeNo())
                && expected.captureOutTradeNo().equals(stored.captureOutTradeNo()) && expected.channelId().equals(stored.channelId())
                && stored.amount() != null && expected.amount().compareTo(stored.amount()) == 0;
    }

    private static String shortText(String value, int max) {
        if (value == null || value.isBlank()) return "—";
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).limit(max).forEach(result::appendCodePoint);
        return result.toString();
    }

    private void failure(String code, String message) {
        lastError = message;
        log.warn("Telegram notification status={}", code);
    }

    static void validateToken(String token) {
        if (token == null || !token.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,100}")) {
            throw new SettingsException("TELEGRAM_TOKEN_INVALID", "机器人 Token 格式不正确", false);
        }
    }

    static void validateChatId(String chatId, boolean required) {
        if (!required && (chatId == null || chatId.isEmpty())) return;
        try {
            if (chatId == null || !chatId.matches("-?[1-9][0-9]{0,18}") || Long.parseLong(chatId) == 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new SettingsException("TELEGRAM_CHAT_ID_INVALID", "请填写正确的数字群 ID（群 ID 通常为负数）", false);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static Path configuredPath(String value) {
        try { return Path.of(value); } catch (RuntimeException ex) { return null; }
    }

    private static ZoneId configuredZone(String value) {
        try { return ZoneId.of(value); } catch (RuntimeException ex) { return ZoneId.of("Asia/Bangkok"); }
    }

    public record SettingsView(boolean enabled, boolean tokenConfigured, String chatId, String lastError) { }
    public record SettingsUpdate(Boolean enabled, String chatId, String botToken) { }
    public record TestResult(boolean success, String code, String message) { }
    private record Settings(boolean enabled, String botToken, String chatId) { }
    record Delivery(String eventId, String chatId, String tokenFingerprint, String text, int attempts,
                    long nextAttemptAt, boolean delivered, Long messageId, String lastError) { }
    private record Dispatch(long nextAt) { }
    private record Capture(String parentOutTradeNo, String captureOutTradeNo, String channelId, BigDecimal amount) { }

    public static final class SettingsException extends RuntimeException {
        private final String code;
        private final boolean storage;
        SettingsException(String code, String message, boolean storage) {
            super(message);
            this.code = code;
            this.storage = storage;
        }
        public String code() { return code; }
        public boolean storage() { return storage; }
    }
}
