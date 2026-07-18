package com.example.payments.payout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MerchantPayoutBatchRequest(
        @NotEmpty @Size(max = 20) List<@Valid MerchantPayoutBatchItemRequest> items,
        @NotBlank String paymentPassword
) {
}
