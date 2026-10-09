package com.example.payments.notification;

import com.example.payments.order.DemoOrder;
import com.example.payments.order.DemoOrderStatus;
import com.example.payments.order.DemoOrderView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramNotificationServiceTest {
    private static final String TOKEN = "12345678:abcdefghijklmnopqrstuvwxyz_123456789";
    private static final String TOKEN_2 = "23456789:abcdefghijklmnopqrstuvwxyz_123456789";
    private static final String CHAT = "-1001234567890";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final FakeTransport transport = new FakeTransport();

    @Test
    void defaultIsDisabledAndNeverContactsTelegramOrCreatesAnOutbox() {
        TelegramNotificationService service = service();
        assertThat(service.settings()).isEqualTo(new TelegramNotificationService.SettingsView(false, false, "", ""));
        service.enqueueConfirmedReceipt(receipt("ORDER-1"));
        service.sendPending();
        assertThat(service.sendTest().code()).isEqualTo("TELEGRAM_NOT_CONFIGURED");
        assertThat(transport.calls).isEmpty();
        assertThat(directory.resolve("outbox")).doesNotExist();
    }

    @Test
    void enqueueOnlyWritesDiskAndSuccessIsDeduplicatedAcrossRestart() throws Exception {
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(receipt("ORDER-1"));
        service.enqueueConfirmedReceipt(receipt("ORDER-1"));
        assertThat(transport.calls).isEmpty();
        assertThat(deliveries()).hasSize(1);
        TelegramNotificationService restarted = service();
        restarted.sendPending();
        assertThat(transport.calls).hasSize(1);
        String message = transport.calls.getFirst().text();
        assertThat(message).contains("收款成功", "¥12.34", "商户", "ali-main", "ORDER-1", "PLATFORM-ORDER-1", "+07:00")
                .doesNotContain("BUYER_SECRET", TOKEN);
        TelegramNotificationService.Delivery delivery = mapper.readValue(deliveries().getFirst().toFile(), TelegramNotificationService.Delivery.class);
        assertThat(delivery.delivered()).isTrue();
        assertThat(delivery.messageId()).isEqualTo(1001L);
        clock.advance(10);
        TelegramNotificationService again = service();
        again.enqueueConfirmedReceipt(receipt("ORDER-1"));
        again.sendPending();
        assertThat(transport.calls).hasSize(1);
    }

    @Test
    void twoInstancesConcurrentlyEnqueueOneEvent() throws Exception {
        TelegramNotificationService first = enabled();
        TelegramNotificationService second = service();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                TelegramNotificationService selected = i % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> selected.enqueueConfirmedReceipt(receipt("ORDER-SAME"))));
            }
            for (var future : futures) future.get();
        }
        assertThat(deliveries()).hasSize(1);
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void queueRejectsNonReceiptsAndManualOrFrozenOrders() throws Exception {
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(null);
        service.enqueueConfirmedReceipt(order("PENDING", DemoOrderStatus.UNPAID, false, false, "1.00"));
        service.enqueueConfirmedReceipt(order("FROZEN", DemoOrderStatus.COMPLETED, true, false, "1.00"));
        service.enqueueConfirmedReceipt(order("MANUAL", DemoOrderStatus.COMPLETED, false, true, "1.00"));
        service.enqueueConfirmedReceipt(order("REFUND", DemoOrderStatus.REFUNDED, false, false, "1.00"));
        service.enqueueConfirmedReceipt(order("ZERO", DemoOrderStatus.COMPLETED, false, false, "0.00"));
        service.sendPending();
        assertThat(deliveries()).isEmpty();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void settingsNeverReturnTokenAndBlankUpdatePreservesStoredToken() throws Exception {
        TelegramNotificationService service = enabled();
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(false, CHAT, " "));
        String json = mapper.writeValueAsString(service.settings());
        assertThat(json).contains("\"tokenConfigured\":true", "\"enabled\":false").doesNotContain(TOKEN, "\"botToken\"");
        assertThat(service().sendTest().success()).isTrue();
        assertThat(transport.calls.getFirst().token()).isEqualTo(TOKEN);
        assertThat(transport.calls.getFirst().text()).contains("测试").doesNotContain("收款成功");
    }

    @Test
    void changedChatOrBotCannotReceiveOldPendingNotifications() throws Exception {
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(receipt("OLD"));
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, "-1009999999999", ""));
        service.sendPending();
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, TOKEN_2));
        service.sendPending();
        assertThat(transport.calls).isEmpty();
        assertThat(service.settings().lastError()).contains("目标配置已变更");
        assertThat(mapper.readValue(deliveries().getFirst().toFile(), TelegramNotificationService.Delivery.class).delivered()).isFalse();
    }

    @Test
    void disabledModeKeepsOldPendingAndDoesNotEnqueueNewEvents() throws Exception {
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(receipt("OLD"));
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(false, CHAT, ""));
        service.enqueueConfirmedReceipt(receipt("DISABLED-EVENT"));
        service.sendPending();
        assertThat(transport.calls).isEmpty();
        assertThat(deliveries()).hasSize(1);
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, ""));
        service.enqueueConfirmedReceipt(receipt("DISABLED-EVENT"));
        service.sendPending();
        assertThat(transport.calls).hasSize(1);
        assertThat(deliveries()).hasSize(1);
        assertThat(day("2026-10-09").entries()).hasSize(2);
    }

    @Test
    void failedDeliveryRetriesAfterBackoffAndRestartsDoNotLoseIt() {
        TelegramNotificationService service = enabled();
        transport.next = TelegramTransport.Result.failure("TELEGRAM_NETWORK_ERROR", "无法连接 Telegram", 0);
        service.enqueueConfirmedReceipt(receipt("RETRY"));
        service.sendPending();
        assertThat(transport.calls).hasSize(1);
        clock.advance(3);
        service().sendPending();
        assertThat(transport.calls).hasSize(1);
        clock.advance(2);
        service().sendPending();
        assertThat(transport.calls).hasSize(2);
    }

    @Test
    void rateLimitPausesAllOrdersAndThePauseSurvivesRestart() {
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(receipt("FIRST"));
        service.enqueueConfirmedReceipt(receipt("SECOND"));
        transport.next = TelegramTransport.Result.failure("TELEGRAM_RATE_LIMITED", "已限流", 30);
        service.sendPending();
        clock.advance(5);
        service().sendPending();
        assertThat(transport.calls).hasSize(1);
        clock.advance(25);
        service().sendPending();
        assertThat(transport.calls).hasSize(2);
    }

    @Test
    void successfulMessagesAreThrottledAcrossInstances() {
        TelegramNotificationService first = enabled();
        first.enqueueConfirmedReceipt(receipt("ONE"));
        first.enqueueConfirmedReceipt(receipt("TWO"));
        first.sendPending();
        service().sendPending();
        clock.advance(2);
        service().sendPending();
        assertThat(transport.calls).hasSize(1);
        clock.advance(1);
        service().sendPending();
        assertThat(transport.calls).hasSize(2);
    }

    @Test
    void malformedConfigurationDoesNotPreventStartupAndCanBeRepaired() throws Exception {
        Files.writeString(directory.resolve("settings.json"), "{invalid");
        TelegramNotificationService service = service();
        assertThat(service.settings().enabled()).isFalse();
        assertThat(service.settings().lastError()).isNotBlank();
        assertThatCode(() -> service.enqueueConfirmedReceipt(receipt("IGNORED"))).doesNotThrowAnyException();
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, TOKEN));
        assertThat(service.settings().enabled()).isTrue();
        assertThat(service.settings().lastError()).isEmpty();
    }

    @Test
    void outboxWriteFailureNeverEscapesPaymentCallback() throws Exception {
        TelegramNotificationService service = enabled();
        Files.writeString(directory.resolve("outbox"), "not a directory");
        assertThatCode(() -> service.enqueueConfirmedReceipt(receipt("DISK-FAILURE"))).doesNotThrowAnyException();
        assertThat(service.settings().lastError()).contains("暂未写入");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void failedAtomicConfigurationWriteKeepsPreviousSavedSettings() {
        FailingMapper failing = new FailingMapper();
        TelegramNotificationService service = new TelegramNotificationService(failing, directory, transport, clock, ZoneId.of("Asia/Bangkok"));
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, TOKEN));
        failing.fail = true;
        assertThatThrownBy(() -> service.saveSettings(new TelegramNotificationService.SettingsUpdate(false, "-200", TOKEN_2)))
                .isInstanceOf(TelegramNotificationService.SettingsException.class);
        assertThat(service.settings().enabled()).isTrue();
        assertThat(service.settings().chatId()).isEqualTo(CHAT);
    }

    @Test
    void invalidTokenAndChatIdAreRejectedWithoutEchoingValues() {
        TelegramNotificationService service = service();
        assertThatThrownBy(() -> service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, "evil/secret")))
                .isInstanceOf(TelegramNotificationService.SettingsException.class).hasMessage("机器人 Token 格式不正确");
        assertThatThrownBy(() -> service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, "https://example.com", TOKEN)))
                .isInstanceOf(TelegramNotificationService.SettingsException.class).hasMessageContaining("数字群 ID");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void explicitTestReportsSanitizedFailureAndDoesNotCreateReceiptOutbox() throws Exception {
        TelegramNotificationService service = enabled();
        transport.next = TelegramTransport.Result.failure("TELEGRAM_CHAT_REJECTED", "请检查群权限", 0);
        TelegramNotificationService.TestResult result = service.sendTest();
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("TELEGRAM_CHAT_REJECTED");
        assertThat(deliveries()).isEmpty();
    }

    @Test
    void dailyTotalsIncludeDisabledReceiptsAndCurrentOrderButNeverReplayOldEvents() throws Exception {
        TelegramNotificationService service = service();
        service.enqueueConfirmedReceipt(receipt("WHILE-DISABLED"));
        service = enabled();
        service.enqueueConfirmedReceipt(receipt("WHILE-DISABLED"));
        service.enqueueConfirmedReceipt(order("ENABLED", DemoOrderStatus.COMPLETED, false, false, "0.66"));
        service.sendPending();
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().text()).contains("今日（2026-10-09）已收 2 笔 / 合计 ¥13.00");
        service().enqueueConfirmedReceipt(receipt("ENABLED"));
        assertThat(day("2026-10-09").entries()).hasSize(2);
        assertThat(day("2026-10-09").entries().values()).containsExactlyInAnyOrder(new BigDecimal("12.34"), new BigDecimal("0.66"));
    }

    @Test
    void confirmationDayUsesConfiguredTimeZoneAndRetryKeepsOriginalTotals() throws Exception {
        clock.instant = Instant.parse("2026-10-09T16:59:59Z"); // 23:59:59 in Bangkok.
        TelegramNotificationService service = enabled();
        service.enqueueConfirmedReceipt(receipt("BEFORE-MIDNIGHT"));
        transport.next = TelegramTransport.Result.failure("TELEGRAM_NETWORK_ERROR", "无法连接", 0);
        service.sendPending();
        String original = transport.calls.getFirst().text();
        clock.advance(2);
        service.enqueueConfirmedReceipt(order("AFTER-MIDNIGHT", DemoOrderStatus.COMPLETED, false, false, "1.00"));
        assertThat(day("2026-10-09").entries()).hasSize(1);
        assertThat(day("2026-10-10").entries()).hasSize(1);
        assertThat(deliveries().stream().map(path -> {
            try { return mapper.readValue(path.toFile(), TelegramNotificationService.Delivery.class).text(); }
            catch (IOException ex) { throw new RuntimeException(ex); }
        })).anySatisfy(text -> assertThat(text).contains("2026-10-10 00:00:01 +07:00", "今日（2026-10-10）已收 1 笔 / 合计 ¥1.00"));
        clock.advance(3);
        service.sendPending();
        clock.advance(5);
        service.sendPending();
        assertThat(transport.calls.stream().filter(call -> call.text().contains("订单号：BEFORE-MIDNIGHT")).map(Call::text))
                .containsExactly(original, original);
        assertThat(day("2026-10-09").entries()).hasSize(1);
    }

    @Test
    void interruptedOutboxWriteRecoversJournalWithoutDoubleAccountingOrChangingTarget() throws Exception {
        TelegramNotificationService service = enabled();
        Files.writeString(directory.resolve("outbox"), "temporarily blocked");
        service.enqueueConfirmedReceipt(receipt("RECOVER"));
        assertThat(directory.resolve("receipts/pending.json")).doesNotExist();
        assertThat(pendingDeliveries()).hasSize(1);
        assertThat(day("2026-10-09").entries()).hasSize(1);
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, "-100999", ""));
        Files.delete(directory.resolve("outbox"));
        clock.advance(86400);
        TelegramNotificationService restarted = service();
        restarted.sendPending();
        assertThat(transport.calls).isEmpty();
        assertThat(directory.resolve("receipts/pending.json")).doesNotExist();
        assertThat(pendingDeliveries()).isEmpty();
        var delivery = mapper.readValue(deliveries().getFirst().toFile(), TelegramNotificationService.Delivery.class);
        assertThat(delivery.chatId()).isEqualTo(CHAT);
        assertThat(delivery.text()).contains("2026-10-09", "今日（2026-10-09）已收 1 笔 / 合计 ¥12.34");
        restarted.enqueueConfirmedReceipt(receipt("RECOVER"));
        assertThat(day("2026-10-09").entries()).hasSize(1);
        assertThat(directory.resolve("receipts/days/2026-10-10.json")).doesNotExist();
    }

    @Test
    void journalRecoversAfterDailyWriteFailureBeforePermanentEventExists() throws Exception {
        ReceiptFailingMapper failing = new ReceiptFailingMapper();
        TelegramNotificationService service = new TelegramNotificationService(failing, directory, transport, clock, ZoneId.of("Asia/Bangkok"));
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, TOKEN));
        failing.rejectEvent = true;
        service.enqueueConfirmedReceipt(receipt("PARTIAL"));
        assertThat(day("2026-10-09").entries()).hasSize(1);
        assertThat(directory.resolve("receipts/pending.json")).exists();
        service().enqueueConfirmedReceipt(receipt("PARTIAL"));
        assertThat(day("2026-10-09").entries()).hasSize(1);
        assertThat(deliveries()).hasSize(1);
        assertThat(directory.resolve("receipts/pending.json")).doesNotExist();
    }

    @Test
    void blockedOldOutboxDoesNotLoseLaterReceiptsOrTheirOriginalTargetSnapshots() throws Exception {
        TelegramNotificationService service = enabled();
        Files.writeString(directory.resolve("outbox"), "temporarily blocked");
        service.enqueueConfirmedReceipt(receipt("A"));
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, "-100999", ""));
        service().enqueueConfirmedReceipt(order("B", DemoOrderStatus.COMPLETED, false, false, "1.00"));
        service().enqueueConfirmedReceipt(order("C", DemoOrderStatus.COMPLETED, false, false, "2.00"));
        assertThat(pendingDeliveries()).hasSize(3);
        assertThat(directory.resolve("receipts/pending.json")).doesNotExist();
        assertThat(day("2026-10-09").entries()).hasSize(3);
        assertThat(day("2026-10-09").entries().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("15.34");
        Files.delete(directory.resolve("outbox"));
        clock.advance(86400);
        TelegramNotificationService restarted = service();
        restarted.sendPending();
        clock.advance(3);
        restarted.sendPending();
        assertThat(pendingDeliveries()).isEmpty();
        assertThat(deliveries()).hasSize(3);
        assertThat(transport.calls).hasSize(2).allSatisfy(call -> assertThat(call.chatId()).isEqualTo("-100999"));
        assertThat(transport.calls.stream().map(Call::text)).anySatisfy(text -> assertThat(text)
                        .contains("订单号：B", "今日（2026-10-09）已收 2 笔 / 合计 ¥13.34"))
                .anySatisfy(text -> assertThat(text).contains("订单号：C", "今日（2026-10-09）已收 3 笔 / 合计 ¥15.34"));
        restarted.enqueueConfirmedReceipt(receipt("A"));
        restarted.enqueueConfirmedReceipt(receipt("B"));
        restarted.enqueueConfirmedReceipt(receipt("C"));
        assertThat(day("2026-10-09").entries()).hasSize(3);
        assertThat(directory.resolve("receipts/days/2026-10-10.json")).doesNotExist();
        assertThat(deliveries()).hasSize(3);
    }

    @Test
    void captureMarkersSurviveRestartAndRequireExactChildChannelAndAmount() {
        TelegramNotificationService service = service();
        DemoOrderView frozen = order("PARENT", DemoOrderStatus.FROZEN, true, false, "12.34");
        service.rememberPendingCapture(frozen, "CAPTURE", new BigDecimal("5.00"));
        TelegramNotificationService restarted = service();
        assertThat(restarted.hasPendingCapture("PARENT", "CAPTURE", "ali-main", new BigDecimal("5"))).isTrue();
        assertThat(restarted.hasPendingCapture("PARENT", "OTHER", "ali-main", new BigDecimal("5"))).isFalse();
        assertThat(restarted.hasPendingCapture("OTHER", "CAPTURE", "ali-main", new BigDecimal("5"))).isFalse();
        assertThat(restarted.hasPendingCapture("PARENT", "CAPTURE", "other-channel", new BigDecimal("5"))).isFalse();
        assertThat(restarted.hasPendingCapture("PARENT", "CAPTURE", "ali-main", new BigDecimal("5.01"))).isFalse();
        service.rememberPendingCapture(frozen, "CAPTURE", new BigDecimal("6.00"));
        assertThat(restarted.hasPendingCapture("PARENT", "CAPTURE", "ali-main", new BigDecimal("5"))).isTrue();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void captureMarkersRejectManualUnfrozenAndInvalidRequestsAndCorruptFiles() throws Exception {
        TelegramNotificationService service = service();
        service.rememberPendingCapture(receipt("REGULAR"), "CAPTURE", BigDecimal.ONE);
        service.rememberPendingCapture(order("MANUAL", DemoOrderStatus.FROZEN, true, true, "10"), "CAPTURE", BigDecimal.ONE);
        service.rememberPendingCapture(order("FROZEN", DemoOrderStatus.FROZEN, true, false, "10"), "", BigDecimal.ONE);
        service.rememberPendingCapture(order("OVER", DemoOrderStatus.FROZEN, true, false, "10"), "CAPTURE", new BigDecimal("11"));
        assertThat(directory.resolve("captures")).doesNotExist();
        service.rememberPendingCapture(order("VALID", DemoOrderStatus.FROZEN, true, false, "10"), "CAPTURE", BigDecimal.ONE);
        try (var files = Files.list(directory.resolve("captures"))) {
            Path file = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
            Files.writeString(file, "broken");
        }
        assertThat(service.hasPendingCapture("VALID", "CAPTURE", "ali-main", BigDecimal.ONE)).isFalse();
        assertThat(transport.calls).isEmpty();
    }

    private TelegramReceiptLedger.Day day(String date) throws IOException {
        return mapper.readValue(directory.resolve("receipts/days/" + date + ".json").toFile(), TelegramReceiptLedger.Day.class);
    }

    private List<Path> pendingDeliveries() throws IOException {
        Path plans = directory.resolve("receipts/pending-deliveries");
        if (!Files.isDirectory(plans)) return List.of();
        try (var files = Files.list(plans)) {
            return files.filter(path -> path.toString().endsWith(".json")).toList();
        }
    }

    private TelegramNotificationService enabled() {
        TelegramNotificationService service = service();
        service.saveSettings(new TelegramNotificationService.SettingsUpdate(true, CHAT, TOKEN));
        return service;
    }

    private TelegramNotificationService service() {
        return new TelegramNotificationService(mapper, directory, transport, clock, ZoneId.of("Asia/Bangkok"));
    }

    private List<Path> deliveries() throws IOException {
        Path outbox = directory.resolve("outbox");
        if (!Files.isDirectory(outbox)) return List.of();
        try (var files = Files.list(outbox)) {
            return files.filter(path -> path.toString().endsWith(".json")).toList();
        }
    }

    private static DemoOrderView receipt(String id) { return order(id, DemoOrderStatus.COMPLETED, false, false, "12.34"); }

    private static DemoOrderView order(String id, DemoOrderStatus status, boolean preauth, boolean supplemented, String amount) {
        DemoOrder order = new DemoOrder(id, "PLATFORM-" + id, "ali-main", "M1", "测试商户", "支付", "BUYER_SECRET",
                new BigDecimal(amount), status, "2026-10-09 00:00:00", preauth);
        order.setSupplemented(supplemented);
        return DemoOrderView.from(order);
    }

    private static final class FakeTransport implements TelegramTransport {
        final List<Call> calls = new ArrayList<>();
        Result next;
        @Override public Result send(String token, String chatId, String text) {
            calls.add(new Call(token, chatId, text));
            Result result = next;
            next = null;
            return result != null ? result : new Result(true, 1000L + calls.size(), "TELEGRAM_SENT", "已发送", 0);
        }
    }
    private record Call(String token, String chatId, String text) { }
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-09T00:00:00Z");
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
    private static final class FailingMapper extends ObjectMapper {
        boolean fail;
        @Override public void writeValue(File resultFile, Object value) throws IOException {
            if (fail) throw new IOException("write rejected");
            super.writeValue(resultFile, value);
        }
    }
    private static final class ReceiptFailingMapper extends ObjectMapper {
        boolean rejectEvent;
        @Override public void writeValue(File file, Object value) throws IOException {
            if (rejectEvent && file.getParentFile().getName().equals("events")) throw new IOException("event write rejected");
            super.writeValue(file, value);
        }
    }
}
