package com.example.payments.gateway.douyin;

import com.example.payments.domain.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A completed split can still contain failed recipients. */
public final class DouyinProfitSharingState {
    private DouyinProfitSharingState() {
    }

    /** Unified responses may contain empty finish fields, or describe a split alongside automatic unfreezing. */
    public static boolean hasFinishEvidence(Map<String, Object> data) {
        Object description = data.get("finish_description");
        if (!nonnegativeAmount(data.get("finish_amount"))
                && !(description instanceof String text && !text.isBlank())) {
            return false;
        }
        return data.get("receivers") == null
                || (data.get("receivers") instanceof List<?> receivers && receivers.isEmpty());
    }

    private static boolean nonnegativeAmount(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return false;
        try {
            return new BigDecimal(value.toString().trim()).signum() >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public static PaymentStatus toPaymentStatus(Map<String, Object> data, boolean completionWithoutReceivers) {
        Object state = data.getOrDefault("state", data.getOrDefault("result", "PROCESSING"));
        String normalized = String.valueOf(state).trim().toUpperCase(Locale.ROOT);
        if (List.of("CLOSED", "FAILED", "FAIL").contains(normalized)) {
            return PaymentStatus.FAILED;
        }
        if (!"FINISHED".equals(normalized) && !"SUCCESS".equals(normalized)) {
            return PaymentStatus.PENDING;
        }
        if (data.get("receivers") != null && !(data.get("receivers") instanceof List<?>)) {
            return PaymentStatus.PENDING;
        }
        if (data.get("receivers") instanceof List<?> receivers && !receivers.isEmpty()) {
            boolean pending = false;
            for (Object item : receivers) {
                if (!(item instanceof Map<?, ?> receiver)) {
                    pending = true;
                    continue;
                }
                String result = String.valueOf(receiver.get("result")).trim().toUpperCase(Locale.ROOT);
                if (List.of("CLOSED", "FAILED", "FAIL").contains(result)) {
                    return PaymentStatus.FAILED;
                }
                pending |= !"SUCCESS".equals(result);
            }
            return pending ? PaymentStatus.PENDING : PaymentStatus.SUCCESS;
        }
        // Finish/unfreeze has no recipient list; return orders use terminal state SUCCESS.
        return completionWithoutReceivers ? PaymentStatus.SUCCESS : PaymentStatus.PENDING;
    }
}
