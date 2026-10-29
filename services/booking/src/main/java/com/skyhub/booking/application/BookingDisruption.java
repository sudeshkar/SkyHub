package com.skyhub.booking.application;

import com.skyhub.booking.domain.DisruptionType;
import java.time.Instant;
import java.time.LocalDate;

/** A disruption on one flight of a booking; {@code segmentIndex} is the flight's position in that booking. */
public record BookingDisruption(int segmentIndex, String flightNumber, LocalDate departureDate, DisruptionType type,
                                Instant revisedDeparture, String reason) {
}
