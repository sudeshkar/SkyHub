package com.skyhub.booking.persistence;

import com.skyhub.booking.application.AccountSessions;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adapter: the account's session epoch is a column read by primary key (ADR-0041). */
@Component
class JdbcAccountSessions implements AccountSessions {

    private final JdbcTemplate jdbc;

    JdbcAccountSessions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean accepts(long accountId, int epoch) {
        List<Integer> current = jdbc.queryForList("SELECT session_epoch FROM traveller_account WHERE id = ?", Integer.class, accountId);
        return current.size() == 1 && current.getFirst() == epoch;
    }
}
