package com.skyhub.booking.application;

import com.skyhub.booking.application.SavedPassengers.Saved;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.SavedPassengerLimitException;
import com.skyhub.booking.domain.SavedPassengerNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The names a traveller keeps on their account to fill a booking with (ADR-0078): names only (no date of birth, passport or contact
 * detail exists anywhere in the system), at most {@value #LIMIT}, saved once however many times they are asked for. Which account is the
 * caller's, never something they can name: it comes from the token. A name is personal data, so nothing here logs or counts one.
 */
@Service
public class SavedPassengerService {

    public static final int LIMIT = 10;
    static final int MAX_NAME = 50;

    /** {@code created} is false when the name was already kept and this is that one. */
    public record Added(Saved passenger, boolean created) {
    }

    private final SavedPassengers passengers;
    private final SavedPassengerMetrics metrics;

    public SavedPassengerService(SavedPassengers passengers, SavedPassengerMetrics metrics) {
        this.passengers = passengers;
        this.metrics = metrics;
    }

    public List<Saved> list(long accountId) {
        return passengers.of(accountId);
    }

    /**
     * @throws InvalidBookingException when a name is missing, blank or too long, before anything is looked at
     * @throws SavedPassengerLimitException when the account keeps {@value #LIMIT} names and this is a new one
     */
    public Added add(long accountId, String firstName, String lastName) {
        String first = clean(firstName);
        String last = clean(lastName);
        // At most ten rows to read, so a repeat is answered without a second place and without a write.
        for (Saved kept : passengers.of(accountId)) {
            if (kept.firstName().equalsIgnoreCase(first) && kept.lastName().equalsIgnoreCase(last)) {
                metrics.record("already_saved");
                return new Added(kept, false);
            }
        }
        Saved saved = passengers.add(accountId, first, last, LIMIT).orElseThrow(() -> {
            metrics.record("full");
            return new SavedPassengerLimitException("Ten names are already saved; remove one to save another");
        });
        metrics.record("added");
        return new Added(saved, true);
    }

    /** @throws SavedPassengerNotFoundException when there is no such name on this account, whether it never existed or is another's */
    public void remove(long accountId, long id) {
        if (!passengers.remove(accountId, id)) {
            metrics.record("not_found");
            throw new SavedPassengerNotFoundException("No saved passenger matches");
        }
        metrics.record("removed");
    }

    private static String clean(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME) {
            throw new InvalidBookingException("A name of 1 to " + MAX_NAME + " characters is needed");
        }
        return trimmed;
    }
}
