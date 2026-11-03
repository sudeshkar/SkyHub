package com.skyhub.booking.persistence;

import com.skyhub.booking.application.FlightBoarding;
import com.skyhub.booking.application.FlightBoardingReport;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter: two reads, each starting from the flight's own index ({@code ix_pnr_segment_date}, date then flight) and reaching the boarding or
 * check-in rows through the booking's unique reference and the rows' primary keys. Work is proportional to the passengers on that one flight
 * (at most the seats on the aircraft), not to all boardings ever recorded, and there is no per-passenger query.
 */
@Component
class JdbcFlightBoardingReport implements FlightBoardingReport {

    private final JdbcTemplate jdbc;

    JdbcFlightBoardingReport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public FlightBoarding of(String flightNumber, LocalDate departureDate) {
        Integer checkedIn = jdbc.queryForObject("""
                SELECT count(*)
                FROM pnr_segment s
                JOIN pnr p ON p.id = s.pnr_id
                JOIN check_in c ON c.locator = p.locator AND c.segment_index = s.segment_order
                WHERE s.departure_date = ? AND s.flight_number = ?""", Integer.class, departureDate, flightNumber);
        List<FlightBoarding.Boarding> boardings = jdbc.query("""
                SELECT b.locator, b.segment_index, b.passenger_index, b.boarded_by, b.boarded_at
                FROM pnr_segment s
                JOIN pnr p ON p.id = s.pnr_id
                JOIN boarded_passenger b ON b.locator = p.locator AND b.segment_index = s.segment_order
                WHERE s.departure_date = ? AND s.flight_number = ?
                ORDER BY b.boarded_at, b.locator, b.passenger_index""",
                (rs, row) -> new FlightBoarding.Boarding(rs.getString("locator"), rs.getInt("segment_index"), rs.getInt("passenger_index"),
                        rs.getString("boarded_by"), rs.getTimestamp("boarded_at").toInstant()),
                departureDate, flightNumber);
        return new FlightBoarding(flightNumber, departureDate, checkedIn == null ? 0 : checkedIn, boardings);
    }
}
