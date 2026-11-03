package com.skyhub.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "These passengers have checked in for a flight on this booking" (ADR-0073, contract:
 * api-specs/events/booking-checked-in.v1.example.json). One event for each check-in that checked someone in, listing only those who were
 * checked in by it. It carries what the traveller already knows (seat, boarding number, times) and the pass only sealed: the pass is a
 * credential and must not be readable on the topic.
 *
 * <p>Each passenger's signed pass travels <strong>sealed</strong> (ADR-0074), as the one-time link tokens do (ADR-0046).
 *
 * <p>{@code departure} is the expected time (the revised one if the flight was delayed); {@code checkInCloses} is when check-in stops.
 */
public record BookingCheckedInEvent(UUID eventId, String type, int version, Instant occurredAt, String locator, String contactEmail,
                                    String flightNumber, LocalDate departureDate, Instant departure, Instant checkInCloses,
                                    List<CheckedInPassenger> passengers) implements NotificationEvent {

    public static final String TYPE = "booking.checked-in";

    /**
     * {@code sealedPass} is the signed boarding pass, sealed for this event type and address (ADR-0074); only the notification service,
     * which holds the key, can open it. It is null in an event sent before passes were attached, and is never printed.
     */
    public record CheckedInPassenger(String firstName, String lastName, String seat, int boardingNumber, String sealedPass) {

        /** A passenger with no pass attached, as in an event from before ADR-0074. */
        public CheckedInPassenger(String firstName, String lastName, String seat, int boardingNumber) {
            this(firstName, lastName, seat, boardingNumber, null);
        }

        @Override
        public String toString() {
            return "CheckedInPassenger[seat=" + seat + ", boardingNumber=" + boardingNumber + "]";
        }
    }

    @Override
    public String toString() {
        return "BookingCheckedInEvent[eventId=" + eventId + ", locator=" + locator + ", flight=" + flightNumber + "]";
    }
}
