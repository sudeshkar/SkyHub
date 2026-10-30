package com.skyhub.booking.application.rebooking;

import java.time.LocalDate;

/** Move one flight of a booking onto another. {@code rebookedBy} is the staff username, for the audit record. */
public record RebookCommand(String locator, int segmentIndex, String flightNumber, LocalDate departureDate, String rebookedBy) {
}
