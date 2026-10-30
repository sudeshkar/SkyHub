package com.skyhub.booking.application;

import com.skyhub.booking.domain.DisruptionType;
import java.time.Instant;
import java.time.LocalDate;

/** {@code revisedDeparture} is for a delay and must be absent for a cancellation. {@code reason} may be null. {@code recordedBy} is the staff username (ADR-0017). */
public record RecordDisruptionCommand(String flightNumber, LocalDate departureDate, DisruptionType type,
                                      Instant revisedDeparture, String reason, String recordedBy) {
}
