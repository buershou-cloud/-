package com.example.payments.web;

import com.example.payments.order.OrderOperationView;
import com.example.payments.payout.MerchantPayoutService;
import com.example.payments.payout.MerchantPayoutView;
import com.example.payments.sharing.ProfitSharingRecordService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderOperationControllerTest {
    @Test
    void listsBothProvidersWithDistinctOutgoingTypesAndNeverExecutesMoneyOperations() {
        ProfitSharingRecordService sharing = mock(ProfitSharingRecordService.class);
        MerchantPayoutService payouts = mock(MerchantPayoutService.class);
        var aliShare = sharing("SAME-ID", "ALIPAY", "2026-09-28 12:00:00");
        var dyShare = sharing("DY-SHARE", "DOUYIN", "2026-09-28 14:00:00");
        when(sharing.search(null, null, null, null, null)).thenReturn(List.of(aliShare, dyShare));
        when(payouts.search(null, null, null, null, null)).thenReturn(List.of(
                payout("SAME-ID", "DOUYIN", "2026-09-28 13:00:00"),
                payout("ALI-PAYOUT", "ALIPAY", "2026-09-28 11:00:00")));

        var response = new OrderOperationController(sharing, payouts).list(null, null, null, null, null);
        var rows = response.records();
        assertThat(response.warnings()).isEmpty();

        assertThat(rows).extracting(OrderOperationView::orderNo)
                .containsExactly("DY-SHARE", "SAME-ID", "SAME-ID", "ALI-PAYOUT");
        assertThat(rows).extracting(OrderOperationView::recordType)
                .containsExactly("PROFIT_SHARING", "PAYOUT", "PROFIT_SHARING", "PAYOUT");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("SUCCESS");
            assertThat(row.amount()).isEqualByComparingTo("2.50");
        });
        assertThat(rows.get(1).recipient()).isEqualTo("138****8000");
        assertThat(rows.get(1).relatedOutTradeNo()).isNull();
        assertThat(rows.get(0).relatedOutTradeNo()).isEqualTo("ORIGINAL-PAYMENT");
        verify(sharing).search(null, null, null, null, null);
        verify(payouts).search(null, null, null, null, null);
        verifyNoMoreInteractions(sharing, payouts);
    }

    @Test
    void pushesHistoricalDateIdentityAndChannelFiltersToBothStores() {
        ProfitSharingRecordService sharing = mock(ProfitSharingRecordService.class);
        MerchantPayoutService payouts = mock(MerchantPayoutService.class);
        when(sharing.search(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(payouts.search(any(), any(), any(), any(), any())).thenReturn(List.of());

        assertThat(new OrderOperationController(sharing, payouts)
                .list("2026-01-01T00:00", "2026-01-31T23:59", "OLD-REQUEST", "PLATFORM-ID", "old-channel").records())
                .isEmpty();

        verify(sharing).search("2026-01-01T00:00", "2026-01-31T23:59", "OLD-REQUEST", "PLATFORM-ID", "old-channel");
        verify(payouts).search("2026-01-01T00:00", "2026-01-31T23:59", "OLD-REQUEST", "PLATFORM-ID", "old-channel");
        verifyNoMoreInteractions(sharing, payouts);
    }

    @Test
    void oneUnavailableStoreDoesNotHideOtherRecordsOrExposeDatabaseErrors() {
        ProfitSharingRecordService sharing = mock(ProfitSharingRecordService.class);
        MerchantPayoutService payouts = mock(MerchantPayoutService.class);
        when(sharing.search(null, null, null, null, null)).thenReturn(List.of(sharing("PS-1", "ALIPAY", "2026-09-28 12:00:00")));
        when(payouts.search(null, null, null, null, null)).thenThrow(new IllegalStateException("internal connection details"));

        var response = new OrderOperationController(sharing, payouts).list(null, null, null, null, null);
        assertThat(response.records()).hasSize(1);
        assertThat(response.warnings()).singleElement().asString()
                .contains("代付记录读取失败", "不完整").doesNotContain("internal connection details");
        verify(sharing).search(null, null, null, null, null);
        verify(payouts).search(null, null, null, null, null);
        verifyNoMoreInteractions(sharing, payouts);
    }

    private static OrderOperationView sharing(String id, String provider, String time) {
        return new OrderOperationView("PROFIT_SHARING", id, "PLATFORM-ID", "ORIGINAL-PAYMENT",
                "channel", provider, "M1", "Merchant", "订单分账", new BigDecimal("2.50"),
                "SUCCESS", time, "rec****ver", "成功");
    }

    private static MerchantPayoutView payout(String id, String provider, String time) {
        return new MerchantPayoutView(1, id, provider, "channel", "OPENID", "138****8000", null,
                new BigDecimal("2.50"), "代付", "备注", null, "PLATFORM-ID", null,
                "SUCCESS", "SUCCESS", "成功", null, time, time, time);
    }
}
