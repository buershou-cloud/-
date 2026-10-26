package com.example.payments.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramReceiptLedgerMigrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void upgradingLegacyBangkokLedgerRebuildsBeijingDaysWithoutChangingDeliverySnapshots() throws Exception {
        record(ledger(mapper, "Asia/Bangkok", "2026-10-09T16:30:00Z"), 1, "10.00");
        Path event = directory.resolve("receipts/events/" + id(1) + ".json");
        Path delivery = directory.resolve("outbox/" + id(1) + ".json");
        byte[] originalEvent = Files.readAllBytes(event);
        byte[] originalDelivery = Files.readAllBytes(delivery);
        Files.delete(directory.resolve("receipts/day-index.json")); // Simulate a ledger from before index metadata existed.

        var beijing = ledger(mapper, "Asia/Shanghai", "2026-10-09T17:30:00Z");
        record(beijing, 2, "5.00");
        assertThat(day("2026-10-10").entries()).containsOnlyKeys(id(1), id(2));
        assertThat(Files.exists(directory.resolve("receipts/days/2026-10-09.json"))).isFalse();
        assertThat(Files.readAllBytes(event)).isEqualTo(originalEvent);
        assertThat(Files.readAllBytes(delivery)).isEqualTo(originalDelivery);
        assertThat(entry(2).text()).isEqualTo("count=2;amount=15.00");
        record(beijing, 1, "10.00");
        assertThat(day("2026-10-10").entries()).hasSize(2);

        // Explicit time-zone overrides can also switch back without rewriting immutable events.
        ledger(mapper, "Asia/Bangkok", "2026-10-09T17:30:00Z").recover();
        assertThat(day("2026-10-09").entries()).containsOnlyKeys(id(1));
        assertThat(day("2026-10-10").entries()).containsOnlyKeys(id(2));
    }

    @Test
    void interruptedRebuildIsRetriedAfterRestartWithoutLosingOrDuplicatingReceipts() throws Exception {
        record(ledger(mapper, "Asia/Bangkok", "2026-10-09T16:30:00Z"), 1, "10.00");
        ObjectMapper failing = new ObjectMapper() {
            @Override public void writeValue(File file, Object value) throws IOException {
                if (value instanceof TelegramReceiptLedger.DayIndex index && index.complete()) {
                    throw new IOException("Index completion unavailable");
                }
                super.writeValue(file, value);
            }
        };
        assertThatThrownBy(() -> record(ledger(failing, "Asia/Shanghai", "2026-10-09T17:30:00Z"), 2, "5.00"))
                .isInstanceOf(IOException.class);
        var index = mapper.readValue(directory.resolve("receipts/day-index.json").toFile(), TelegramReceiptLedger.DayIndex.class);
        assertThat(index.complete()).isFalse();
        assertThat(directory.resolve("receipts/events/" + id(2) + ".json")).doesNotExist();

        record(ledger(mapper, "Asia/Shanghai", "2026-10-09T17:30:00Z"), 2, "5.00");
        assertThat(day("2026-10-10").entries()).hasSize(2);
        assertThat(entry(2).text()).isEqualTo("count=2;amount=15.00");
        assertThat(mapper.readValue(directory.resolve("receipts/day-index.json").toFile(), TelegramReceiptLedger.DayIndex.class).complete()).isTrue();
    }

    @Test
    void pendingLegacyReceiptRecoversIntoItsBeijingDayAndKeepsOriginalMessage() throws Exception {
        ObjectMapper failing = new ObjectMapper() {
            @Override public void writeValue(File file, Object value) throws IOException {
                if (file.getParentFile().getName().equals("events")) throw new IOException("Event write unavailable");
                super.writeValue(file, value);
            }
        };
        assertThatThrownBy(() -> record(ledger(failing, "Asia/Bangkok", "2026-10-09T16:30:00Z"), 1, "10.00"))
                .isInstanceOf(IOException.class);
        assertThat(directory.resolve("receipts/pending.json")).exists();
        var beijing = ledger(mapper, "Asia/Shanghai", "2026-10-09T17:30:00Z");
        beijing.recover();
        assertThat(directory.resolve("receipts/pending.json")).doesNotExist();
        assertThat(day("2026-10-10").entries()).containsOnlyKeys(id(1));
        assertThat(entry(1).day()).isEqualTo("2026-10-09");
        assertThat(entry(1).text()).isEqualTo("count=1;amount=10.00");
        record(beijing, 2, "5.00");
        assertThat(entry(2).text()).isEqualTo("count=2;amount=15.00");
    }

    @Test
    void missingLegacyTimestampFailsWithoutGuessingTheDateOrDiscardingMoney() throws Exception {
        record(ledger(mapper, "Asia/Bangkok", "2026-10-09T16:30:00Z"), 1, "10.00");
        Path event = directory.resolve("receipts/events/" + id(1) + ".json");
        var json = mapper.readTree(event.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("confirmedAt");
        mapper.writeValue(event.toFile(), json);
        byte[] originalDay = Files.readAllBytes(directory.resolve("receipts/days/2026-10-09.json"));
        assertThatThrownBy(() -> record(ledger(mapper, "Asia/Shanghai", "2026-10-09T17:30:00Z"), 2, "5.00"))
                .isInstanceOf(IOException.class).hasMessageContaining("Invalid receipt journal");
        assertThat(Files.readAllBytes(directory.resolve("receipts/days/2026-10-09.json"))).isEqualTo(originalDay);
        assertThat(directory.resolve("receipts/events/" + id(2) + ".json")).doesNotExist();
    }

    @Test
    void orphanedDailyAmountsAreNotSilentlyRemovedWhenEventJournalIsMissing() throws Exception {
        record(ledger(mapper, "Asia/Bangkok", "2026-10-09T16:30:00Z"), 1, "10.00");
        Files.delete(directory.resolve("receipts/events/" + id(1) + ".json"));
        assertThatThrownBy(() -> ledger(mapper, "Asia/Shanghai", "2026-10-09T17:30:00Z").recover())
                .isInstanceOf(IOException.class).hasMessageContaining("no matching timestamped event");
        assertThat(day("2026-10-09").entries()).containsOnlyKeys(id(1));
    }

    private TelegramReceiptLedger ledger(ObjectMapper mapper, String zone, String instant) {
        return new TelegramReceiptLedger(mapper, directory, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), ZoneId.of(zone));
    }

    private static void record(TelegramReceiptLedger ledger, int event, String amount) throws IOException {
        ledger.record(id(event), new BigDecimal(amount), "-100123", "a".repeat(64),
                (confirmedAt, totals) -> "count=" + totals.count() + ";amount=" + totals.amount());
    }

    private TelegramReceiptLedger.Day day(String day) throws IOException {
        return mapper.readValue(directory.resolve("receipts/days/" + day + ".json").toFile(), TelegramReceiptLedger.Day.class);
    }

    private TelegramReceiptLedger.Entry entry(int id) throws IOException {
        return mapper.readValue(directory.resolve("receipts/events/" + id(id) + ".json").toFile(), TelegramReceiptLedger.Entry.class);
    }

    private static String id(int number) { return String.format("%064x", number); }
}
