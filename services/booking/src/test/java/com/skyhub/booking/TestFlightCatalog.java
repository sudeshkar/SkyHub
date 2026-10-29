package com.skyhub.booking;

import com.skyhub.booking.application.FlightCatalog;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Stands in for the flight-search service in integration tests: every flight operates except SK999. */
@TestConfiguration(proxyBeanMethods = false)
public class TestFlightCatalog {

    public static final String UNKNOWN_FLIGHT = "SK999";

    @Bean
    @Primary
    FlightCatalog testFlightCatalog() {
        return (flightNumber, departureDate) -> !UNKNOWN_FLIGHT.equals(flightNumber);
    }
}
