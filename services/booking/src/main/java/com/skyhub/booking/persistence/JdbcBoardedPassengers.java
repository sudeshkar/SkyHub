package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BoardedPassengers;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter: boarding is one row per passenger per flight in its own table (ADR-0060), so scanning a pass never touches the booking. The
 * primary key makes the first scan win even when two gates scan the same pass at the same instant.
 */
@Component
class JdbcBoardedPassengers implements BoardedPassengers {

    private final JdbcTemplate jdbc;

    JdbcBoardedPassengers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean board(String locator, int segmentIndex, int passengerIndex, String by, Instant at) {
        return jdbc.update("INSERT INTO boarded_passenger (locator, segment_index, passenger_index, boarded_by, boarded_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING", locator, segmentIndex, passengerIndex, by, Timestamp.from(at)) == 1;
    }

    @Override
    public boolean isBoarded(String locator, int segmentIndex, int passengerIndex) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarded_passenger WHERE locator = ? AND segment_index = ? AND passenger_index = ?)",
                Boolean.class, locator, segmentIndex, passengerIndex));
    }

    @Override
    public boolean anyBoarded(String locator, int segmentIndex, Collection<Integer> passengerIndexes) {
        if (passengerIndexes.isEmpty()) {
            return false;
        }
        Integer[] who = passengerIndexes.toArray(Integer[]::new);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM boarded_passenger WHERE locator = ? AND segment_index = ? AND passenger_index = ANY (?))",
                Boolean.class, locator, segmentIndex, (Object) who));
    }
}
