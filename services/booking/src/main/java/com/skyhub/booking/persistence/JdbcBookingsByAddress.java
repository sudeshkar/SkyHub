package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BookingCursor;
import com.skyhub.booking.application.BookingFilter;
import com.skyhub.booking.application.BookingsByAddress;
import com.skyhub.booking.application.UpcomingCursor;
import com.skyhub.booking.application.view.BookingSummary;
import com.skyhub.booking.application.view.PnrView.SegmentView;
import com.skyhub.booking.domain.PnrStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads "my bookings" with three statements however long the list is: the page of bookings (served by the address index, already in
 * order), their flights, and their passenger counts. No entities, so nothing is loaded that the summary does not show, and nothing
 * encrypted is read at all.
 */
@Component
class JdbcBookingsByAddress implements BookingsByAddress {

    private final JdbcTemplate jdbc;

    JdbcBookingsByAddress(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Row(long id, String locator, PnrStatus status, Instant createdAt) {
    }

    /** A confirmed booking with a flight today or later (ADR-0042). */
    private static final String UPCOMING = "(status = 'CONFIRMED' AND EXISTS (SELECT 1 FROM pnr_segment s WHERE s.pnr_id = pnr.id "
            + "AND s.departure_date >= ?))";

    @Override
    public List<BookingSummary> newestAfter(String emailIndex, BookingFilter filter, LocalDate today, BookingCursor after, int limit) {
        // Keyset paging (ADR-0038): the page starts at a position in the index, so a page far back costs the same as the first.
        // The reference breaks ties between bookings made in the same instant, so none is skipped or shown twice. The filter is a
        // condition on the same walk (ADR-0042): the query reads this traveller's bookings in order until the page is full.
        List<Object> arguments = new ArrayList<>();
        arguments.add(emailIndex);
        StringBuilder where = new StringBuilder("contact_email_index = ? ");
        switch (filter) {
            case UPCOMING -> throw new IllegalArgumentException("Upcoming bookings are listed soonest first: use upcomingSoonest");
            case PAST -> {
                where.append("AND NOT ").append(UPCOMING).append(' ');
                arguments.add(java.sql.Date.valueOf(today));
            }
            case ALL -> {
            }
        }
        if (after != null) {
            where.append("AND (created_at, locator) < (?, ?) ");
            arguments.add(Timestamp.from(after.createdAt()));
            arguments.add(after.locator());
        }
        arguments.add(limit);
        List<Row> rows = jdbc.query("SELECT id, locator, status, created_at FROM pnr WHERE " + where
                        + "ORDER BY created_at DESC, locator DESC LIMIT ?",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), PnrStatus.valueOf(rs.getString(3)),
                        rs.getTimestamp(4).toInstant()), arguments.toArray());
        return summaries(rows);
    }

    /**
     * Soonest next flight first (ADR-0044). The next flight date is computed per booking, so no index can serve the order: the query
     * computes it for this traveller's confirmed bookings, keeps those that have one, and returns the page after the cursor in
     * (next date, reference) order. Bounded by one traveller's own bookings.
     */
    @Override
    public List<BookingSummary> upcomingSoonest(String emailIndex, LocalDate today, UpcomingCursor after, int limit) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(java.sql.Date.valueOf(today));
        arguments.add(emailIndex);
        String start = "";
        if (after != null) {
            start = "AND (next_date, locator) > (?, ?) ";
            arguments.add(java.sql.Date.valueOf(after.nextDeparture()));
            arguments.add(after.locator());
        }
        arguments.add(limit);
        List<Row> rows = jdbc.query("SELECT id, locator, status, created_at FROM ("
                        + "SELECT p.id, p.locator, p.status, p.created_at, "
                        + "(SELECT min(s.departure_date) FROM pnr_segment s WHERE s.pnr_id = p.id AND s.departure_date >= ?) AS next_date "
                        + "FROM pnr p WHERE p.contact_email_index = ? AND p.status = 'CONFIRMED') t "
                        + "WHERE next_date IS NOT NULL " + start + "ORDER BY next_date, locator LIMIT ?",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), PnrStatus.valueOf(rs.getString(3)),
                        rs.getTimestamp(4).toInstant()), arguments.toArray());
        return summaries(rows);
    }

    /** The flights and passenger counts of a page of bookings, in two statements however long the page is. */
    private List<BookingSummary> summaries(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long[] ids = rows.stream().map(Row::id).toArray(Long[]::new);
        Map<Long, List<SegmentView>> flights = new HashMap<>();
        jdbc.query("SELECT pnr_id, flight_number, departure_date FROM pnr_segment WHERE pnr_id = ANY (?) ORDER BY pnr_id, segment_order",
                rs -> {
                    flights.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
                            .add(new SegmentView(rs.getString(2), rs.getDate(3).toLocalDate()));
                }, (Object) ids);
        Map<Long, Integer> passengers = new HashMap<>();
        jdbc.query("SELECT pnr_id, count(*) FROM pnr_passenger WHERE pnr_id = ANY (?) GROUP BY pnr_id",
                rs -> {
                    passengers.put(rs.getLong(1), rs.getInt(2));
                }, (Object) ids);
        return rows.stream()
                .map(r -> new BookingSummary(r.locator(), r.status(), flights.getOrDefault(r.id(), List.of()),
                        passengers.getOrDefault(r.id(), 0), r.createdAt()))
                .toList();
    }

    @Override
    public boolean owns(String emailIndex, String locator) {
        Boolean owns = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pnr WHERE locator = ? AND contact_email_index = ?)", Boolean.class, locator, emailIndex);
        return Boolean.TRUE.equals(owns);
    }
}
