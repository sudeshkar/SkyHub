package com.skyhub.booking.persistence;

import com.skyhub.booking.application.UnindexedContacts;
import com.skyhub.booking.domain.ContactEmail;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads and updates bookings that have no address index yet. Served by the partial index on the unindexed rows. */
@Component
class JdbcUnindexedContacts implements UnindexedContacts {

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    JdbcUnindexedContacts(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public List<Contact> after(long id, int limit) {
        return jdbc.query("SELECT id, contact_email FROM pnr WHERE contact_email_index IS NULL AND id > ? ORDER BY id LIMIT ?",
                (rs, n) -> new Contact(rs.getLong(1), readable(rs.getString(2))), id, limit);
    }

    /**
     * An address that cannot be decrypted (a changed key, a damaged row) comes back as null, never allowed to stop the rest. The
     * backfill counts these and reports them once per run; logging each here would be a line per row at every start.
     */
    private ContactEmail readable(String stored) {
        try {
            return new ContactEmail(cipher.decrypt(stored));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    @Override
    public boolean setIndex(long id, String index) {
        return jdbc.update("UPDATE pnr SET contact_email_index = ? WHERE id = ? AND contact_email_index IS NULL", index, id) == 1;
    }
}
