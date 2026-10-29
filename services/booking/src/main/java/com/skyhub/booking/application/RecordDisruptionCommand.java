package com.skyhub.booking.application;

import com.skyhub.booking.domain.DisruptionType;
import java.time.Instant;
import java.time.LocalDate;

/** {@code revisedDeparture} is for a delay and must be absent for a cancellation. {@code reason} may be null. */
public record RecordDisruptionCommand(String flightNumber, LocalDate departureDate, DisruptionType type,
                                      Instant revisedDeparture, String reason) {
}
