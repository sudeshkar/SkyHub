package com.skyhub.booking;

import com.skyhub.booking.application.FlightCatalog;
import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the flight-search service in integration tests: every flight operates except SK999.
 * Departure times are relative to now so check-in windows can be tested without moving the clock:
 * SK777 leaves in 30 hours (check-in not open yet), SK888 in 30 minutes (already closed), every other
 * flight in 5 hours (open).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestFlightCatalog {

    public static final String UNKNOWN_FLIGHT = "SK999";
    public static final String NOT_YET_OPEN_FLIGHT = "SK777";
    public static final String CLOSED_FLIGHT = "SK888";
    /** Flies DXB to JFK; every other flight flies DXB to LHR. */
    public static final String OTHER_ROUTE_FLIGHT = "SK610";

    /** Every flight costs 100.00 USD per passenger, so the price of a test booking is easy to predict. */
    public static final Money FARE = Money.of("100.00", "USD");

    /** Every flight takes seven hours, so a replacement lands seven hours after it leaves. */
    public static final Duration FLIGHT_TIME = Duration.ofHours(7);

    /** Fixed when the tests start, so asking about the same flight twice gives the same time. */
    private static final Instant START = Instant.now();

    @Bean
    @Primary
    FlightCatalog testFlightCatalog() {
        return (flightNumber, departureDate) -> (switch (flightNumber) {
            case UNKNOWN_FLIGHT -> Optional.<Instant>empty();
            case NOT_YET_OPEN_FLIGHT -> Optional.of(START.plus(Duration.ofHours(30)));
            case CLOSED_FLIGHT -> Optional.of(START.plus(Duration.ofMinutes(30)));
            default -> Optional.of(START.plus(Duration.ofHours(5)));
        }).map(departure -> new FlightInfo(departure, FARE, "DXB", flightNumber.equals(OTHER_ROUTE_FLIGHT) ? "JFK" : "LHR",
                departure.plus(FLIGHT_TIME)));
    }
}
