package com.skyhub.booking.persistence;

import com.skyhub.booking.application.BookingSessions;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter: one row per booking that anyone has signed out of, read by primary key (ADR-0057). A booking with no row is at epoch 0.
 * Raising it is one statement, so two sign-outs at once both count and neither is lost.
 */
@Component
class JdbcBookingSessions implements BookingSessions {

    private final JdbcTemplate jdbc;

    JdbcBookingSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int currentEpoch(String locator) {
        List<Integer> current = jdbc.queryForList("SELECT epoch FROM booking_session_epoch WHERE locator = ?", Integer.class, locator);
        return current.isEmpty() ? 0 : current.getFirst();
    }

    @Override
    public boolean accepts(String locator, int epoch) {
        return currentEpoch(locator) == epoch;
    }

    @Override
    public void endAll(String locator) {
        jdbc.update("INSERT INTO booking_session_epoch (locator, epoch) VALUES (?, 1) "
                + "ON CONFLICT (locator) DO UPDATE SET epoch = booking_session_epoch.epoch + 1", locator);
    }
}
