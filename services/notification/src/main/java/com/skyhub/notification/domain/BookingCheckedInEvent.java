package com.skyhub.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "These passengers have checked in for a flight on this booking" (ADR-0073, contract:
 * api-specs/events/booking-checked-in.v1.example.json). One event for each check-in that checked someone in, listing only those who were
 * checked in by it. It carries what the traveller already knows (seat, boarding number, times) and <strong>no boarding pass text</strong>:
 * the pass is a credential and does not belong on the topic.
 *
 * <p>{@code departure} is the expected time (the revised one if the flight was delayed); {@code checkInCloses} is when check-in stops.
 */
public record BookingCheckedInEvent(UUID eventId, String type, int version, Instant occurredAt, String locator, String contactEmail,
                                    String flightNumber, LocalDate departureDate, Instant departure, Instant checkInCloses,
                                    List<CheckedInPassenger> passengers) implements NotificationEvent {

    public static final String TYPE = "booking.checked-in";

    public record CheckedInPassenger(String firstName, String lastName, String seat, int boardingNumber) {
    }

    @Override
    public String toString() {
        return "BookingCheckedInEvent[eventId=" + eventId + ", locator=" + locator + ", flight=" + flightNumber + "]";
    }
}
