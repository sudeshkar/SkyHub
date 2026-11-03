package com.skyhub.booking.application;

import java.util.List;
import java.util.Optional;

/**
 * Port: the names an account keeps (ADR-0078). The application owns the interface; the persistence layer implements it, so nothing here
 * knows the names are encrypted or where they are stored. Every call is for one account, and there is no way to ask for another's.
 */
public interface SavedPassengers {

    /** A saved name and the id the account uses to remove it. */
    record Saved(long id, String firstName, String lastName) {
        @Override
        public String toString() {
            return "Saved[id=" + id + "]";   // a name is personal data: never printed
        }
    }

    /** The account's names, oldest first. */
    List<Saved> of(long accountId);

    /** @return the new name, or empty when the account already keeps {@code limit}; two requests at once cannot go over it */
    Optional<Saved> add(long accountId, String firstName, String lastName, int limit);

    /** @return whether a name of this account was removed; another account's id removes nothing */
    boolean remove(long accountId, long id);
}
