package com.skyhub.booking.application;

import java.time.LocalDate;

/**
 * Port: does a flight operate on a date? The application owns this interface; an adapter in the
 * integration package implements it over HTTP (dependency inversion), so tests and other transports
 * can replace it.
 */
public interface FlightCatalog {

    /**
     * @throws FlightCatalogUnavailableException when the timetable cannot be checked right now
     */
    boolean operates(String flightNumber, LocalDate departureDate);
}
