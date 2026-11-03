package com.skyhub.booking.persistence;

import com.skyhub.booking.application.SavedPassengers;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Adapter: one row for each saved name, the names encrypted like every other personal datum (ADR-0078). Reading is one indexed query for
 * one account (at most ten rows). Adding takes a lock on the account's own row first, so two requests at once for the same account cannot
 * both see nine and both add a tenth; different accounts never wait for each other.
 */
@Component
class JdbcSavedPassengers implements SavedPassengers {

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;
    private final TransactionOperations transactions;
    private final Clock clock;

    JdbcSavedPassengers(JdbcTemplate jdbc, PiiCipher cipher, TransactionOperations transactions, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public List<Saved> of(long accountId) {
        return jdbc.query("SELECT id, first_name, last_name FROM saved_passenger WHERE account_id = ? ORDER BY id",
                (rs, row) -> new Saved(rs.getLong("id"), cipher.decrypt(rs.getString("first_name")), cipher.decrypt(rs.getString("last_name"))),
                accountId);
    }

    @Override
    public Optional<Saved> add(long accountId, String firstName, String lastName, int limit) {
        return transactions.execute(status -> {
            jdbc.query("SELECT id FROM traveller_account WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), accountId);
            Integer kept = jdbc.queryForObject("SELECT count(*) FROM saved_passenger WHERE account_id = ?", Integer.class, accountId);
            if (kept != null && kept >= limit) {
                return Optional.empty();
            }
            Long id = jdbc.queryForObject("INSERT INTO saved_passenger (account_id, first_name, last_name, created_at) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, accountId, cipher.encrypt(firstName), cipher.encrypt(lastName), Timestamp.from(clock.instant()));
            return Optional.of(new Saved(id, firstName, lastName));
        });
    }

    @Override
    public boolean remove(long accountId, long id) {
        return jdbc.update("DELETE FROM saved_passenger WHERE account_id = ? AND id = ?", accountId, id) == 1;
    }
}
