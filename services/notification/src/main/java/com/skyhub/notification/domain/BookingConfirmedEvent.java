package com.skyhub.notification.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "A booking was confirmed" (ADR-0019), as the booking service publishes it. The shape is the contract in
 * api-specs/events/booking-confirmed.v1.example.json. Fields added later are ignored. The contact email is
 * personal data: {@link #toString()} never prints it.
 */
public record BookingConfirmedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                    String contactEmail, List<Passenger> passengers, List<Segment> segments,
                                    BigDecimal totalAmount, String currency) implements NotificationEvent {

    public static final String TYPE = "booking.confirmed";

    public record Passenger(String firstName, String lastName) {
    }

    public record Segment(String flightNumber, LocalDate departureDate) {
    }

    @Override
    public String toString() {
        return "BookingConfirmedEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
