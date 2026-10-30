package com.skyhub.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "A booking was cancelled by the traveller" (ADR-0019, contract: api-specs/events/booking-cancelled.v1.example.json).
 * {@code refund} is what the fare rules said about the money; it is absent for a booking that was never paid. It says
 * what <em>will</em> happen, not that it has: the refund itself is settled afterwards (ADR-0016).
 */
public record BookingCancelledEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                    String contactEmail, List<BookingConfirmedEvent.Segment> segments,
                                    Refund refund) implements NotificationEvent {

    public static final String TYPE = "booking.cancelled";

    /** {@code decision} is FULL_REFUND or NO_REFUND. */
    public record Refund(String decision, BigDecimal amount, String currency) {
    }

    @Override
    public String toString() {
        return "BookingCancelledEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
