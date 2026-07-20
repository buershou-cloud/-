package com.example.payments.payout;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record MerchantPayoutBatchItemRequest(
        @NotBlank String channelId,
        String outBizNo,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String recipientType,
        @NotBlank String recipient,
        String recipientName,
        String orderTitle,
        String remark,
        String transferSceneId,
        String sceneInfoType,
        String sceneInfoContent,
        List<MerchantPayoutSceneReportInfoRequest> sceneReportInfos
) {

    public MerchantPayoutBatchItemRequest(
            String channelId,
            String outBizNo,
            BigDecimal amount,
            String recipientType,
            String recipient,
            String recipientName,
            String orderTitle,
            String remark,
            String transferSceneId,
            String sceneInfoType,
            String sceneInfoContent
    ) {
        this(
                channelId,
                outBizNo,
                amount,
                recipientType,
                recipient,
                recipientName,
                orderTitle,
                remark,
                transferSceneId,
                sceneInfoType,
                sceneInfoContent,
                List.of()
        );
    }

    public MerchantPayoutCreateRequest toCreateRequest(String paymentPassword) {
        return new MerchantPayoutCreateRequest(
                channelId,
                outBizNo,
                amount,
                recipientType,
                recipient,
                recipientName,
                orderTitle,
                remark,
                transferSceneId,
                sceneInfoType,
                sceneInfoContent,
                sceneReportInfos,
                paymentPassword
        );
    }
}
