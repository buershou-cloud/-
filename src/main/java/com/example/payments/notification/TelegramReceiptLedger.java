package com.example.payments.notification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local confirmed-receipt accounting, independent of Telegram delivery and the enabled switch. */
final class TelegramReceiptLedger {
    private final ObjectMapper mapper;
    private final Path directory;
    private final Clock clock;
    private final ZoneId timeZone;

    TelegramReceiptLedger(ObjectMapper mapper, Path directory, Clock clock, ZoneId timeZone) {
        this.mapper = mapper;
        this.directory = directory;
        this.clock = clock;
        this.timeZone = timeZone;
    }

    void record(String eventId, BigDecimal amount, String chatId, String tokenFingerprint,
                MessageFactory messageFactory) throws IOException {
        Path receipts = receipts();
        NotificationFiles.locked(receipts.resolve("ledger.lock"), true, () -> {
            recoverLocked(receipts);
            Path event = receipts.resolve("events").resolve(eventId + ".json");
            if (Files.exists(event)) return false;
            Instant confirmedAt = clock.instant();
            String day = confirmedAt.atZone(timeZone).toLocalDate().toString();
            Map<String, BigDecimal> entries = readDay(receipts, day);
            entries.putIfAbsent(eventId, amount);
            Totals totals = new Totals(entries.size(), entries.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            Entry entry = new Entry(eventId, day, amount, confirmedAt.toEpochMilli(), totals.count(), totals.amount(),
                    chatId, tokenFingerprint, messageFactory.create(confirmedAt, totals));
            // Accounting and an independent per-event delivery plan commit before any outbox work.
            NotificationFiles.write(mapper, receipts.resolve("pending.json"), entry);
            apply(receipts, entry);
            return true;
        });
        recoverDeliveries(receipts);
    }

    void recover() throws IOException {
        Path receipts = receipts();
        if (Files.exists(receipts.resolve("pending.json"))) {
            NotificationFiles.locked(receipts.resolve("ledger.lock"), true, () -> {
                recoverLocked(receipts);
                return true;
            });
        }
        recoverDeliveries(receipts);
    }

    private void recoverLocked(Path receipts) throws IOException {
        Path pending = receipts.resolve("pending.json");
        if (Files.exists(pending)) apply(receipts, NotificationFiles.read(mapper, pending, Entry.class));
    }

    private void apply(Path receipts, Entry entry) throws IOException {
        validateEntry(entry);
        Map<String, BigDecimal> entries = readDay(receipts, entry.day());
        BigDecimal previous = entries.putIfAbsent(entry.eventId(), entry.amount());
        if (previous != null && previous.compareTo(entry.amount()) != 0) throw new IOException("Receipt amount conflict");
        NotificationFiles.write(mapper, receipts.resolve("days").resolve(entry.day() + ".json"), new Day(entries));
        Path event = receipts.resolve("events").resolve(entry.eventId() + ".json");
        if (!Files.exists(event)) NotificationFiles.write(mapper, event, entry);
        if (entry.chatId() != null) {
            Path plan = receipts.resolve("pending-deliveries").resolve(entry.eventId() + ".json");
            if (!Files.exists(plan)) NotificationFiles.write(mapper, plan, entry);
        }
        Files.deleteIfExists(receipts.resolve("pending.json"));
    }

    private static void validateEntry(Entry entry) throws IOException {
        if (entry == null || entry.eventId() == null || !entry.eventId().matches("[a-f0-9]{64}")
                || entry.day() == null || !entry.day().matches("\\d{4}-\\d{2}-\\d{2}")
                || entry.amount() == null || entry.amount().signum() <= 0 || entry.todayCount() <= 0
                || entry.todayAmount() == null || entry.todayAmount().compareTo(entry.amount()) < 0
                || entry.text() == null || entry.text().length() > 4096
                || (entry.chatId() == null) != (entry.tokenFingerprint() == null)) {
            throw new IOException("Invalid receipt journal");
        }
        if (entry.chatId() != null) {
            TelegramNotificationService.validateChatId(entry.chatId(), true);
            if (!entry.tokenFingerprint().matches("[a-f0-9]{64}")) throw new IOException("Invalid receipt target");
        }
    }

    private void recoverDeliveries(Path receipts) throws IOException {
        Path plans = receipts.resolve("pending-deliveries");
        if (!Files.isDirectory(plans)) return;
        NotificationFiles.locked(plans.resolve("deliveries.lock"), true, () -> {
            java.util.List<Path> files;
            try (var stream = Files.list(plans)) {
                files = stream.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.json")).sorted().toList();
            }
            IOException failure = null;
            for (Path file : files) {
                try {
                    Entry entry = NotificationFiles.read(mapper, file, Entry.class);
                    validateEntry(entry);
                    if (entry.chatId() == null || !file.getFileName().toString().equals(entry.eventId() + ".json")) {
                        throw new IOException("Invalid pending delivery identity");
                    }
                    Path delivery = directory.resolve("outbox").resolve(entry.eventId() + ".json");
                    if (!Files.exists(delivery)) {
                        NotificationFiles.write(mapper, delivery, new TelegramNotificationService.Delivery(entry.eventId(),
                                entry.chatId(), entry.tokenFingerprint(), entry.text(), 0, entry.confirmedAt(), false, null, ""));
                    }
                    Files.deleteIfExists(file);
                } catch (IOException | RuntimeException ex) {
                    // One damaged or unwritable delivery must not block other independent plans.
                    failure = new IOException("Pending receipt delivery unavailable");
                }
            }
            if (failure != null) throw failure;
            return true;
        });
    }

    private Map<String, BigDecimal> readDay(Path receipts, String day) throws IOException {
        Path file = receipts.resolve("days").resolve(day + ".json");
        if (!Files.exists(file)) return new LinkedHashMap<>();
        // Daily accounting can exceed the small configuration/outbox limit on busy payment systems.
        Day record = mapper.readValue(file.toFile(), Day.class);
        if (record == null || record.entries() == null) throw new IOException("Invalid daily receipt ledger");
        for (var item : record.entries().entrySet()) {
            if (item.getKey() == null || !item.getKey().matches("[a-f0-9]{64}")
                    || item.getValue() == null || item.getValue().signum() <= 0) throw new IOException("Invalid daily receipt entry");
        }
        return new LinkedHashMap<>(record.entries());
    }

    private Path receipts() throws IOException {
        if (directory == null) throw new IOException("Receipt directory unavailable");
        return directory.resolve("receipts");
    }

    record Totals(long count, BigDecimal amount) { }
    record Day(Map<String, BigDecimal> entries) { }
    record Entry(String eventId, String day, BigDecimal amount, long confirmedAt, long todayCount,
                 BigDecimal todayAmount, String chatId, String tokenFingerprint, String text) { }
    interface MessageFactory { String create(Instant confirmedAt, Totals totals); }
}
