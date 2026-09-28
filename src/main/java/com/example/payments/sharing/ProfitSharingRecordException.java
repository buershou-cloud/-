package com.example.payments.sharing;

import com.example.payments.gateway.GatewayException;

/** A local audit failure must never become a reason to try another funding channel. */
public class ProfitSharingRecordException extends GatewayException {
    public ProfitSharingRecordException(String message, Throwable cause) {
        super("PROFIT_SHARING_RECORD_ERROR", message, cause);
    }

    public ProfitSharingRecordException(String code, String message) {
        super(code, message);
    }
}
