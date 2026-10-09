package com.example.payments.order;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.RefundCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.payments.order.ConfirmedReceiptTestSupport.orders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConfirmedReceiptOrderTest {
    private final ConfirmedReceiptTestSupport.MemorySink sink = new ConfirmedReceiptTestSupport.MemorySink();
    private final DemoOrderService service = orders(sink);

    @Test
    void onlyTrustedSuccessTransitionQueuesAndCallbackQueryReplaysDoNot() {
        create("PAY", false, PaymentStatus.CREATED);
        assertThat(sink.receipts).isEmpty();
        service.recordAlipayNotify("PAY", "TRADE-PAY", "ali-main", money("10.00"), "TRADE_SUCCESS", true);
        service.recordAlipayNotify("PAY", "TRADE-PAY", "ali-main", money("10.00"), "TRADE_FINISHED", true);
        service.recordPaymentResult("PAY", "TRADE-PAY", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        assertThat(sink.receipts).hasSize(1);
        assertThat(sink.receipts.get("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    @Test
    void newSynchronousCollectionQueuesButOldSignatureAndHistoricalSuccessDoNot() {
        create("OLD", false, PaymentStatus.SUCCESS);
        service.recordPaymentResult("OLD", "TRADE-OLD", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        service.recordPaymentCreated("NEW", "TRADE-NEW", "ali-main", "M1", "Merchant", "付款码", "Payment",
                money("10.00"), false, PaymentStatus.SUCCESS, true);
        assertThat(sink.receipts.keySet()).containsExactly("NEW");
    }

    @Test
    void waitingOrClosedReplayDoesNotReopenHistoricalOrManuallyCompletedOrder() {
        for (String order : List.of("OLD", "MANUAL")) {
            create(order, false, "OLD".equals(order) ? PaymentStatus.SUCCESS : PaymentStatus.CREATED);
            if ("MANUAL".equals(order)) service.complete(order);
            for (String status : List.of("WAIT_BUYER_PAY", "TRADE_CLOSED", "TRADE_SUCCESS")) {
                service.recordAlipayNotify(order, "TRADE-" + order, "ali-main", money("10.00"), status,
                        "TRADE_SUCCESS".equals(status));
                assertThat(service.view(order).status()).isEqualTo(DemoOrderStatus.COMPLETED);
            }
        }
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void historicalCompletionCannotBeReopenedByQueryCancelOrCreateReplays() {
        create("OLD", false, PaymentStatus.SUCCESS);
        service.recordPaymentResult("OLD", "TRADE-OLD", "ali-main", PaymentStatus.CLOSED);
        service.recordPaymentCreated("OLD", "TRADE-OLD", "ali-main", "M1", "Merchant", "Payment", "Payment",
                money("10.00"), false, PaymentStatus.CLOSED, false);
        assertThat(service.view("OLD").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        service.recordPaymentResult("OLD", "TRADE-OLD", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void manualSupplementCompleteShareAndRefundNeverQueue() {
        create("MANUAL", false, PaymentStatus.CREATED);
        service.complete("MANUAL");
        service.markProfitShared("MANUAL");
        RefundCreateRequest refund = new RefundCreateRequest("MANUAL", "TRADE-MANUAL", money("2.00"), "REFUND",
                null, null, List.of("ali-main"), Map.of());
        service.recordRefund(refund, response("MANUAL", "TRADE-MANUAL"));
        service.recordPaymentResult("MANUAL", "TRADE-MANUAL", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        create("SUPP", false, PaymentStatus.CREATED);
        service.manualSupplement("SUPP");
        service.uncomplete("SUPP");
        service.recordPaymentResult("SUPP", "TRADE-SUPP", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void freezeAndSuccessfulUnfreezeNeverQueue() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.recordAlipayNotify("AUTH", "AUTH-1", "ali-main", money("10.00"), "TRADE_SUCCESS", false);
        service.recordPreauthUnfreeze(new PreauthUnfreezeRequest("AUTH", "AUTH-1", "UNFREEZE", money("10.00"),
                null, null, List.of("ali-main"), Map.of()), response("AUTH", "AUTH-1"));
        assertThat(service.view("AUTH").status()).isEqualTo(DemoOrderStatus.UNFROZEN);
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void channelTradeAmountOrMissingIdentityCannotQualify() {
        create("CHANNEL", false, PaymentStatus.CREATED);
        service.recordPaymentResult("CHANNEL", "TRADE-CHANNEL", "other", PaymentStatus.SUCCESS, true, money("10.00"));
        create("TRADE", false, PaymentStatus.CREATED);
        service.recordPaymentResult("TRADE", "different", "ali-main", PaymentStatus.SUCCESS, true, money("10.00"));
        create("AMOUNT", false, PaymentStatus.CREATED);
        service.recordPaymentResult("AMOUNT", "TRADE-AMOUNT", "ali-main", PaymentStatus.SUCCESS, true, money("1.00"));
        create("MISSING", false, PaymentStatus.CREATED);
        service.recordPaymentResult("MISSING", null, "ali-main", PaymentStatus.SUCCESS, true, null);
        service.recordAlipayNotify("UNKNOWN", "TRADE-UNKNOWN", "ali-main", money("10.00"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).isEmpty();
        // Existing local state behavior is preserved; only notification qualification is restricted.
        assertThat(service.view("CHANNEL").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    @Test
    void realPartialCaptureQueuesActualCollectedAmountOnOriginalOrder() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.convertPreauthToPay("AUTH", "CAPTURE-TRADE", "ali-main", money("3.00"), true);
        assertThat(sink.receipts.keySet()).containsExactly("AUTH");
        assertThat(sink.receipts.get("AUTH").amount()).isEqualByComparingTo("3.00");
        assertThat(service.view("AUTH").amount()).isEqualByComparingTo("10.00");
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3.00"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).hasSize(1);
    }

    @Test
    void markedPendingCaptureMayQueueLaterSuccessEvenAfterLegacyCompletion() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.rememberPendingCapture("AUTH", "AUTH_PAY_1", "ali-main", money("3.00"));
        service.convertPreauthToPay("AUTH", "AUTH_PAY_1", "ali-main", money("3.00"), false);
        assertThat(sink.receipts).isEmpty();
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3.00"), "WAIT_BUYER_PAY", false);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3.00"), "TRADE_SUCCESS", true);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3.00"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).hasSize(1);
        assertThat(sink.receipts.get("AUTH").amount()).isEqualByComparingTo("3.00");
    }

    @Test
    void rememberedCaptureRequiresExactChildChannelAndAmountAndNeverOpensHistory() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.rememberPendingCapture("AUTH", "AUTH_PAY_1", "ali-main", money("3.00"));
        service.convertPreauthToPay("AUTH", "AUTH_PAY_1", "ali-main", money("3.00"), false);
        service.recordAlipayNotify("AUTH_PAY_2", "CAPTURE", "ali-main", money("3.00"), "TRADE_SUCCESS", true);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "other", money("3.00"), "TRADE_SUCCESS", true);
        // Restore the original channel using the existing legacy path, without notification evidence.
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("3.00"), "WAIT_BUYER_PAY", false);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("4.00"), "TRADE_SUCCESS", true);
        create("HISTORICAL", true, PaymentStatus.SUCCESS);
        service.convertPreauthToPay("HISTORICAL", "OLD-CAPTURE");
        service.recordAlipayNotify("HISTORICAL_PAY_1", "OLD-CAPTURE", "ali-main", money("10.00"), "TRADE_SUCCESS", true);
        create("NORMAL", false, PaymentStatus.CREATED);
        service.recordAlipayNotify("NORMAL_PAY_1", "CAPTURE", "ali-main", money("10.00"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void failedNotificationEnqueueOrMarkerNeverFailsPaymentStateUpdate() {
        DemoOrderService failing = orders(new ConfirmedReceiptSink() {
            @Override public void enqueueConfirmedReceipt(DemoOrderView order) { throw new IllegalStateException("test"); }
            @Override public void rememberPendingCapture(DemoOrderView order, String child, BigDecimal amount) {
                throw new IllegalStateException("test");
            }
            @Override public boolean hasPendingCapture(String parent, String child, String channel, BigDecimal amount) {
                throw new IllegalStateException("test");
            }
        });
        failing.recordPaymentCreated("PAY", null, "ali-main", "M1", "Merchant", "Payment", money("10.00"),
                false, PaymentStatus.CREATED);
        assertThatCode(() -> failing.recordPaymentResult("PAY", "TRADE", "ali-main", PaymentStatus.SUCCESS,
                true, money("10.00"))).doesNotThrowAnyException();
        failing.recordPaymentCreated("AUTH", "AUTH-1", "ali-main", "M1", "Merchant", "Preauth", money("10.00"),
                true, PaymentStatus.SUCCESS);
        assertThatCode(() -> failing.rememberPendingCapture("AUTH", "AUTH_PAY_1", "ali-main", money("3.00")))
                .doesNotThrowAnyException();
        assertThatCode(() -> failing.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("3.00"),
                "TRADE_SUCCESS", true)).doesNotThrowAnyException();
        assertThat(failing.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    private void create(String outTradeNo, boolean preauth, PaymentStatus status) {
        service.recordPaymentCreated(outTradeNo, "TRADE-" + outTradeNo, "ali-main", "M1", "Merchant", "Payment",
                money("10.00"), preauth, status);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static GatewayResponse response(String outTradeNo, String tradeNo) {
        return new GatewayResponse("ali-main", PaymentStatus.SUCCESS, "10000", "Success", outTradeNo, tradeNo,
                null, null, Map.of(), List.of());
    }
}
