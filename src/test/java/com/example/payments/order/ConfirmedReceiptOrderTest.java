package com.example.payments.order;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.RefundCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.payments.order.ConfirmedReceiptTestSupport.orders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        service.recordAlipayNotify("AUTH", "TRADE-AUTH", "ali-main", money("10.00"), "TRADE_SUCCESS", false);
        service.recordPreauthUnfreeze(new PreauthUnfreezeRequest("AUTH", "TRADE-AUTH", "UNFREEZE", money("10.00"),
                null, null, List.of("ali-main"), Map.of()), response("AUTH", "TRADE-AUTH"));
        assertThat(service.view("AUTH").status()).isEqualTo(DemoOrderStatus.UNFROZEN);
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void channelTradeAmountOrMissingIdentityCannotQualify() {
        create("PAY", false, PaymentStatus.CREATED);
        assertThatThrownBy(() -> service.recordPaymentResult("PAY", "TRADE-PAY", "other", PaymentStatus.SUCCESS, true, money("10")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordPaymentResult("PAY", "different", "ali-main", PaymentStatus.SUCCESS, true, money("10")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordPaymentResult("PAY", "TRADE-PAY", "ali-main", PaymentStatus.SUCCESS, true, money("1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordPaymentResult("PAY", null, "ali-main", PaymentStatus.SUCCESS, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAlipayNotify("UNKNOWN", "T", "ali-main", money("10"), "TRADE_SUCCESS", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.view("PAY").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(sink.receipts).isEmpty();
    }

    @Test
    void realPartialCaptureQueuesActualCollectedAmountOnIndependentCollectionOrder() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.reservePreauthCapture(capture("AUTH", "AUTH_PAY_1", "TRADE-AUTH"), "ali-main");
        service.recordCaptureResult("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", PaymentStatus.SUCCESS, money("3"), true);
        assertThat(sink.receipts.keySet()).containsExactly("AUTH_PAY_1");
        assertThat(service.view("AUTH_PAY_1").refundableAmount()).isEqualByComparingTo("3");
        assertThat(service.view("AUTH").amount()).isEqualByComparingTo("10");
        assertThat(service.view("AUTH").preAuthorization()).isTrue();
        assertThat(service.view("AUTH").preauthUnfreezeRemainingAmount()).isEqualByComparingTo("7");
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).hasSize(1);
    }

    @Test
    void reservedPendingCaptureStaysUnpaidUntilItsExactCallbackSucceeds() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.reservePreauthCapture(capture("AUTH", "AUTH_PAY_1", "TRADE-AUTH"), "ali-main");
        service.recordCaptureResult("AUTH_PAY_1", null, "ali-main", PaymentStatus.PENDING, null, false);
        assertThat(service.view("AUTH").status()).isEqualTo(DemoOrderStatus.FROZEN);
        assertThat(service.view("AUTH_PAY_1").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(sink.receipts).isEmpty();
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3"), "WAIT_BUYER_PAY", false);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3"), "TRADE_SUCCESS", true);
        service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE-TRADE", "ali-main", money("3"), "TRADE_SUCCESS", true);
        assertThat(sink.receipts).hasSize(1);
        assertThat(sink.receipts.get("AUTH_PAY_1").amount()).isEqualByComparingTo("3");
    }

    @Test
    void reservedCaptureRequiresExactChildChannelAndAmountAndNeverUsesNamingHeuristics() {
        create("AUTH", true, PaymentStatus.SUCCESS);
        service.reservePreauthCapture(capture("AUTH", "AUTH_PAY_1", "TRADE-AUTH"), "ali-main");
        assertThatThrownBy(() -> service.recordAlipayNotify("AUTH_PAY_2", "CAPTURE", "ali-main", money("3"), "TRADE_SUCCESS", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "other", money("3"), "TRADE_SUCCESS", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("4"), "TRADE_SUCCESS", true))
                .isInstanceOf(IllegalArgumentException.class);
        create("NORMAL", false, PaymentStatus.CREATED);
        create("NORMAL_PAY_1", false, PaymentStatus.CREATED);
        service.recordAlipayNotify("NORMAL_PAY_1", "TRADE-NORMAL_PAY_1", "ali-main", money("10"), "TRADE_SUCCESS", true);
        assertThat(service.view("NORMAL").status()).isEqualTo(DemoOrderStatus.UNPAID);
        assertThat(service.view("NORMAL_PAY_1").status()).isEqualTo(DemoOrderStatus.COMPLETED);
        assertThat(sink.receipts.keySet()).containsExactly("NORMAL_PAY_1");
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
        assertThatCode(() -> failing.reservePreauthCapture(capture("AUTH", "AUTH_PAY_1", "AUTH-1"), "ali-main"))
                .doesNotThrowAnyException();
        assertThatCode(() -> failing.recordAlipayNotify("AUTH_PAY_1", "CAPTURE", "ali-main", money("3.00"),
                "TRADE_SUCCESS", true)).doesNotThrowAnyException();
        assertThat(failing.view("PAY").status()).isEqualTo(DemoOrderStatus.COMPLETED);
    }

    private static PreauthCaptureRequest capture(String parent, String child, String authNo) {
        return new PreauthCaptureRequest(parent, child, authNo, "Capture", money("3"), null, null,
                "NOT_COMPLETE", null, List.of("ali-main"), Map.of());
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
