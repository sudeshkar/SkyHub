package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.FlightStatus;
import java.time.LocalDate;

/**
 * Port: what is known about a dated flight beyond the timetable. Search and lookup ask it; where the answer comes from
 * (today, a board fed by Kafka) is not their business. O(1) per question, so a search stays fast.
 */
@FunctionalInterface
public interface FlightStatusSource {

    FlightStatus statusOf(String flightNumber, LocalDate date);

    /** Nothing is ever reported: every flight is scheduled. */
    static FlightStatusSource none() {
        return (flightNumber, date) -> FlightStatus.SCHEDULED;
    }
}
