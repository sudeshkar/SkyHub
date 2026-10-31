package com.skyhub.booking.application;

import com.skyhub.booking.domain.ContactEmail;
import java.util.List;

/** Port: the bookings that were made before contact addresses were indexed (ADR-0034), read in key order. */
public interface UnindexedContacts {

    /** {@code email} is null when the stored address could not be read; the booking is still returned so the walk does not stop. */
    record Contact(long id, ContactEmail email) {
    }

    /** The next {@code limit} unindexed bookings with an id above {@code id}, in id order. */
    List<Contact> after(long id, int limit);

    /** Sets the index of a booking that still has none. @return false if it already had one (so nothing changed) */
    boolean setIndex(long id, String index);
}
