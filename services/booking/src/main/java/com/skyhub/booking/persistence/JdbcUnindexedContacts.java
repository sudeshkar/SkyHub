package com.skyhub.booking.persistence;

import com.skyhub.booking.application.UnindexedContacts;
import com.skyhub.booking.domain.ContactEmail;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads and updates bookings that have no address index yet. Served by the partial index on the unindexed rows. */
@Component
class JdbcUnindexedContacts implements UnindexedContacts {

    private static final Logger log = LoggerFactory.getLogger(JdbcUnindexedContacts.class);

    private final JdbcTemplate jdbc;
    private final PiiCipher cipher;

    JdbcUnindexedContacts(JdbcTemplate jdbc, PiiCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public List<Contact> after(long id, int limit) {
        return jdbc.query("SELECT id, contact_email FROM pnr WHERE contact_email_index IS NULL AND id > ? ORDER BY id LIMIT ?",
                (rs, n) -> new Contact(rs.getLong(1), readable(rs.getLong(1), rs.getString(2))), id, limit);
    }

    /** An address that cannot be decrypted (a changed key, a damaged row) is reported and left, never allowed to stop the rest. */
    private ContactEmail readable(long id, String stored) {
        try {
            return new ContactEmail(cipher.decrypt(stored));
        } catch (RuntimeException unreadable) {
            log.atError().addKeyValue("pnr.id", id).log("Booking address could not be read, so it was not indexed");
            return null;
        }
    }

    @Override
    public boolean setIndex(long id, String index) {
        return jdbc.update("UPDATE pnr SET contact_email_index = ? WHERE id = ? AND contact_email_index IS NULL", index, id) == 1;
    }
}
