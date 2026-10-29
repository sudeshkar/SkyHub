package com.skyhub.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Port: what do we know about a flight on a date? The application owns this interface; an adapter in the
 * integration package implements it over HTTP (dependency inversion), so tests and other transports
 * can replace it.
 */
public interface FlightCatalog {

    /**
     * @return the flight's departure time and fare, or empty if it does not operate on that date
     * @throws FlightCatalogUnavailableException when the timetable cannot be checked right now
     */
    Optional<FlightInfo> find(String flightNumber, LocalDate departureDate);

    default Optional<Instant> departure(String flightNumber, LocalDate departureDate) {
        return find(flightNumber, departureDate).map(FlightInfo::departure);
    }

    default boolean operates(String flightNumber, LocalDate departureDate) {
        return find(flightNumber, departureDate).isPresent();
    }
}
