package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BoardingSequence;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One row per flight and date holds the next free number. A single upsert reserves the whole block, so
 * there is no read-then-write for two requests to interleave in, and the row lock is held only for that statement
 * (or until the surrounding transaction ends, so a rolled-back check-in gives its block back).
 */
@Component
class JdbcBoardingSequence implements BoardingSequence {

    private static final String RESERVE = """
            INSERT INTO boarding_sequence (flight_number, departure_date, next_value) VALUES (?, ?, ? + 1)
            ON CONFLICT (flight_number, departure_date)
            DO UPDATE SET next_value = boarding_sequence.next_value + ?
            RETURNING next_value - ?
            """;

    private final JdbcTemplate jdbc;

    JdbcBoardingSequence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int reserve(String flightNumber, LocalDate departureDate, int count) {
        Integer first = jdbc.queryForObject(RESERVE, Integer.class, flightNumber, departureDate, count, count, count);
        if (first == null) {
            throw new IllegalStateException("Boarding sequence returned nothing");
        }
        return first;
    }
}
