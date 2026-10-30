package com.skyhub.booking.application.view;

import java.math.BigDecimal;

/** What happened to the money when a paid booking was cancelled (ADR-0016). */
public record RefundView(Status status, BigDecimal amount, String currency) {

    public enum Status {
        /** Given back. */
        REFUNDED,
        /** Owed; the provider has not confirmed yet. Cancelling again retries. */
        PENDING,
        /** Cancelled too late: nothing is given back. */
        NOT_REFUNDABLE
    }
}
