package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BookingCursor;
import com.skyhub.booking.application.BookingsByAddress;
import com.skyhub.booking.application.view.BookingSummary;
import com.skyhub.booking.application.view.PnrView.SegmentView;
import com.skyhub.booking.domain.PnrStatus;
import java.sql.Timestamp;
import java.time.Instant;
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

    @Override
    public List<BookingSummary> newestAfter(String emailIndex, BookingCursor after, int limit) {
        // Keyset paging (ADR-0038): the page starts at a position in the index, so a page far back costs the same as the first.
        // The reference breaks ties between bookings made in the same instant, so none is skipped or shown twice.
        String start = after == null ? "" : "AND (created_at, locator) < (?, ?) ";
        Object[] arguments = after == null ? new Object[] {emailIndex, limit}
                : new Object[] {emailIndex, Timestamp.from(after.createdAt()), after.locator(), limit};
        List<Row> rows = jdbc.query("SELECT id, locator, status, created_at FROM pnr WHERE contact_email_index = ? " + start
                        + "ORDER BY created_at DESC, locator DESC LIMIT ?",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), PnrStatus.valueOf(rs.getString(3)),
                        rs.getTimestamp(4).toInstant()), arguments);
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
