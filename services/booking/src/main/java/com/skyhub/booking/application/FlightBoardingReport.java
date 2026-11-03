package com.skyhub.booking.application;

import java.time.LocalDate;

/**
 * Port: how a flight's boarding stands (ADR-0065). The application owns the question; the persistence layer answers it, so the web layer
 * never learns how boarding is stored. It does not check that the flight exists: a flight nobody has boarded is an empty answer.
 */
public interface FlightBoardingReport {

    FlightBoarding of(String flightNumber, LocalDate departureDate);
}
