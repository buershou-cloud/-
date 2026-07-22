package com.example.payments.payout;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    void validatesDouyinTransferRequestIp() {
        assertThat(MerchantPayoutService.normalizedTransferRequestIp("121.43.135.6"))
                .isEqualTo("121.43.135.6");
        assertThat(MerchantPayoutService.normalizedTransferRequestIp("2001:db8::1"))
                .isEqualTo("2001:db8::1");
        assertThatThrownBy(() -> MerchantPayoutService.normalizedTransferRequestIp("api.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerchantPayoutService.normalizedTransferRequestIp("121.43.135.999"))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void buildsBothRequiredReportItemsForCommissionPayouts() {
        MerchantPayoutCreateRequest request = douyinRequest(
                List.of(
                        new MerchantPayoutSceneReportInfoRequest("岗位类型", "推广员"),
                        new MerchantPayoutSceneReportInfoRequest("报酬说明", "7 月推广佣金")
                )
        );

        assertThat(MerchantPayoutService.douyinSceneReportInfos(request)).containsExactly(
                Map.of("info_type", "岗位类型", "info_content", "推广员"),
                Map.of("info_type", "报酬说明", "info_content", "7 月推广佣金")
        );
    }

    @Test
    void rejectsCommissionPayoutWithSceneNameAsReportType() {
        MerchantPayoutCreateRequest request = new MerchantPayoutCreateRequest(
                "douyin-channel",
                "PAYOUT-001",
                new BigDecimal("1.00"),
                "DOUYIN_PHONE",
                "13800000000",
                "测试收款人",
                "商家代付",
                "推广费用",
                "1003",
                "佣金报酬",
                "推广费用",
                "paypass123"
        );

        assertThatThrownBy(() -> MerchantPayoutService.douyinSceneReportInfos(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("岗位类型、报酬说明");
    }

    private static MerchantPayoutCreateRequest douyinRequest(
            List<MerchantPayoutSceneReportInfoRequest> reportInfos
    ) {
        return new MerchantPayoutCreateRequest(
                "douyin-channel",
                "PAYOUT-001",
                new BigDecimal("1.00"),
                "DOUYIN_PHONE",
                "13800000000",
                "测试收款人",
                "商家代付",
                "推广费用",
                "1003",
                null,
                null,
                reportInfos,
                "paypass123"
        );
    }
}
