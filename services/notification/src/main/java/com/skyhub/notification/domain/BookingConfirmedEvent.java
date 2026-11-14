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
 *
 * <p>Since ADR-0087 each segment also carries its route and times and each passenger their seats, so the confirmation can be an
 * itinerary. An event from before that has none of them: those fields are then null (or an empty list of seats), never guessed.
 */
public record BookingConfirmedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                    String contactEmail, List<Passenger> passengers, List<Segment> segments,
                                    BigDecimal totalAmount, String currency) implements NotificationEvent {

    public static final String TYPE = "booking.confirmed";

    /** {@code seats} holds the passenger's seat on each flight, in segment order; empty in an event from before ADR-0087. */
    public record Passenger(String firstName, String lastName, List<String> seats) {

        public Passenger {
            seats = seats == null ? List.of() : List.copyOf(seats);
        }

        public Passenger(String firstName, String lastName) {
            this(firstName, lastName, List.of());
        }
    }

    /**
     * One flight. {@code origin}, {@code destination}, {@code departure} and {@code arrival} are null in an event from before ADR-0087;
     * {@code arrival} is also null when flight-search did not know it.
     */
    public record Segment(String flightNumber, LocalDate departureDate, String origin, String destination, Instant departure,
                          Instant arrival) {

        public Segment(String flightNumber, LocalDate departureDate) {
            this(flightNumber, departureDate, null, null, null, null);
        }
    }

    @Override
    public String toString() {
        return "BookingConfirmedEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
