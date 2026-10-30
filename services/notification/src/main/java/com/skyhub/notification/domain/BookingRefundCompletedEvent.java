package com.skyhub.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * "A refund that was pending has now been paid" (ADR-0031, contract: api-specs/events/booking-refund-completed.v1.example.json).
 * Sent only when the refund did not go through straight away; a refund paid at once is announced by the cancellation email.
 */
public record BookingRefundCompletedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                          String contactEmail, BigDecimal amount, String currency)
        implements NotificationEvent {

    public static final String TYPE = "booking.refund-completed";

    @Override
    public String toString() {
        return "BookingRefundCompletedEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
