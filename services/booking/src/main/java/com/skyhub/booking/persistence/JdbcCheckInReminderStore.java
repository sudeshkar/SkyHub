package com.skyhub.booking.persistence;

import com.skyhub.booking.application.CheckInReminderStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Plain SQL on purpose: these are set-based questions over the bookings of the next day or two, answered by
 * {@code ix_pnr_segment_date}, and the claim is a single insert whose primary key decides the winner.
 */
@Component
class JdbcCheckInReminderStore implements CheckInReminderStore {

    /** Confirmed, not reminded, not checked in, and the flight is not cancelled. */
    private static final String WAITING = """
            FROM pnr_segment s JOIN pnr p ON p.id = s.pnr_id
            WHERE p.status = 'CONFIRMED'
              AND NOT EXISTS (SELECT 1 FROM check_in_reminder r WHERE r.locator = p.locator AND r.segment_index = s.segment_order)
              AND NOT EXISTS (SELECT 1 FROM check_in c WHERE c.locator = p.locator AND c.segment_index = s.segment_order)
              AND NOT EXISTS (SELECT 1 FROM disruption d WHERE d.flight_number = s.flight_number
                              AND d.departure_date = s.departure_date AND d.type = 'CANCELLED')
            """;

    private final JdbcTemplate jdbc;

    JdbcCheckInReminderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FlightDay> flightsWithBookingsWaiting(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT DISTINCT s.flight_number, s.departure_date " + WAITING
                        + " AND s.departure_date BETWEEN ? AND ?",
                (rs, row) -> new FlightDay(rs.getString(1), rs.getObject(2, LocalDate.class)), from, to);
    }

    @Override
    public List<Waiting> waitingOn(FlightDay flight, int limit) {
        return jdbc.query("SELECT p.locator, s.segment_order " + WAITING
                        + " AND s.flight_number = ? AND s.departure_date = ? ORDER BY p.id LIMIT ?",
                (rs, row) -> new Waiting(rs.getString(1), rs.getInt(2)), flight.flightNumber(), flight.departureDate(), limit);
    }

    @Override
    public boolean claim(Waiting waiting, Instant at) {
        return jdbc.update("INSERT INTO check_in_reminder (locator, segment_index, sent_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", waiting.locator(), waiting.segmentIndex(), Timestamp.from(at)) == 1;
    }
}
