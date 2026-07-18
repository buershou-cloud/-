package com.example.payments.payout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantPayoutServiceTest {

    @Test
    void mapsAlipayTransferStatuses() {
        assertThat(MerchantPayoutService.alipayStatus("SUCCESS")).isEqualTo("SUCCESS");
        assertThat(MerchantPayoutService.alipayStatus("DEALING")).isEqualTo("PROCESSING");
        assertThat(MerchantPayoutService.alipayStatus("FAIL")).isEqualTo("FAILED");
    }

    @Test
    void mapsDouyinTransferStatuses() {
        assertThat(MerchantPayoutService.douyinStatus("ACCEPTED")).isEqualTo("PROCESSING");
        assertThat(MerchantPayoutService.douyinStatus("TRANSFERING")).isEqualTo("PROCESSING");
        assertThat(MerchantPayoutService.douyinStatus("SUCCESS")).isEqualTo("SUCCESS");
        assertThat(MerchantPayoutService.douyinStatus("FAIL")).isEqualTo("FAILED");
    }

    @Test
    void onlyAcceptsRecipientTypesForTheSelectedProvider() {
        assertThat(MerchantPayoutService.normalizedRecipientType("ALIPAY", "alipay_open_id"))
                .isEqualTo("ALIPAY_OPEN_ID");
        assertThat(MerchantPayoutService.normalizedRecipientType("DOUYIN", "douyin_phone"))
                .isEqualTo("DOUYIN_PHONE");
        assertThatThrownBy(() -> MerchantPayoutService.normalizedRecipientType("ALIPAY", "DOUYIN_OPEN_ID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
