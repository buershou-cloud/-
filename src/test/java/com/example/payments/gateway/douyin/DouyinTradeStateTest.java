package com.example.payments.gateway.douyin;

import com.example.payments.domain.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DouyinTradeStateTest {

    @ParameterizedTest
    @CsvSource({
            "SUCCESS, SUCCESS",
            "REFUND, SUCCESS",
            "NOTPAY, PAYING",
            "USERPAYING, PAYING",
            "CLOSED, CLOSED",
            "PAYERROR, FAILED"
    })
    void mapsOfficialDouyinTradeStates(String tradeState, PaymentStatus expected) {
        assertThat(DouyinTradeState.toPaymentStatus(tradeState)).isEqualTo(expected);
    }

    @Test
    void doesNotTreatAlipayTradeStateAsDouyinSuccess() {
        assertThat(DouyinTradeState.toPaymentStatus("TRADE_SUCCESS"))
                .isEqualTo(PaymentStatus.UNKNOWN);
    }
}
