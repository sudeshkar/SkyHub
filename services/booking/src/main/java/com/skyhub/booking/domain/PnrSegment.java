package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One flight of a booked itinerary. {@code departureDate} is the UTC departure date. */
@Embeddable
public record PnrSegment(
        @Column(name = "flight_number", nullable = false, length = 8) String flightNumber,
        @Column(name = "departure_date", nullable = false) LocalDate departureDate) {
}
