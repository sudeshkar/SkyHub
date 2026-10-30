package com.skyhub.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port: which bookings still need a "check-in is open" reminder (ADR-0029). Only confirmed bookings on flights that are not
 * cancelled, whose flight has not been checked in to and that have not been reminded already, are ever returned.
 */
public interface CheckInReminderStore {

    /** A flight on a date. */
    record FlightDay(String flightNumber, LocalDate departureDate) {
    }

    /** One booking's flight that is waiting for its reminder. {@code segmentIndex} is its position in the booking. */
    record Waiting(String locator, int segmentIndex) {
    }

    /** The distinct flights with someone waiting, among those departing from {@code from} to {@code to} (UTC dates). */
    List<FlightDay> flightsWithBookingsWaiting(LocalDate from, LocalDate to);

    /** Up to {@code limit} bookings waiting on this flight. Claimed ones no longer appear, so repeating makes progress. */
    List<Waiting> waitingOn(FlightDay flight, int limit);

    /** @return true if this caller is the one that queues the reminder; false if someone else already did */
    boolean claim(Waiting waiting, Instant at);
}
