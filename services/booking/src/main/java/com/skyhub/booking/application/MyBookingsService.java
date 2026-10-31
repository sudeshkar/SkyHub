package com.skyhub.booking.application;

import com.skyhub.booking.application.view.BookingSummary;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.TravellerAccount;
import com.skyhub.booking.persistence.TravellerAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A signed-in traveller's bookings (ADR-0034): those whose contact address is the account's. Ownership is one equality on the
 * keyed hash of the address, which an account only has once it was proved by an emailed link (ADR-0033). Opening a booking returns
 * the ordinary manage token for that one booking, so nothing downstream knows how the traveller got in.
 */
@Service
public class MyBookingsService {

    private static final Logger log = LoggerFactory.getLogger(MyBookingsService.class);

    /** Most bookings listed. A traveller with more sees the newest and is told there are older ones. */
    public static final int LIMIT = 50;

    private final TravellerAccountRepository accounts;
    private final BookingsByAddress bookings;
    private final ManageBookingService manage;
    private final ManageTokenIssuer tokens;
    private final MyBookingsMetrics metrics;
    private final Clock clock;

    public MyBookingsService(TravellerAccountRepository accounts, BookingsByAddress bookings, ManageBookingService manage,
                             ManageTokenIssuer tokens, MyBookingsMetrics metrics, Clock clock) {
        this.clock = clock;
        this.accounts = accounts;
        this.bookings = bookings;
        this.manage = manage;
        this.tokens = tokens;
        this.metrics = metrics;
    }

    /**
     * @param cursor where the previous page ended (its {@code nextCursor}); null or empty for the newest bookings
     * @param when   {@code all} (or none), {@code upcoming} or {@code past} (ADR-0042); anything else is refused
     */
    public BookingList list(long accountId, String cursor, String when) {
        BookingCursor after = BookingCursor.decodeOrNull(cursor);   // refused before anything is read
        BookingFilter filter = BookingFilter.fromWire(when);
        // One more than the limit is how we know there are more, without counting them all.
        List<BookingSummary> found = bookings.newestAfter(indexOf(accountId), filter, LocalDate.now(clock.withZone(ZoneOffset.UTC)), after, LIMIT + 1);
        metrics.listed();
        if (found.size() <= LIMIT) {
            return new BookingList(found, null);
        }
        List<BookingSummary> page = found.subList(0, LIMIT);
        BookingSummary last = page.getLast();
        return new BookingList(page, new BookingCursor(last.createdAt(), last.locator()).encode());
    }

    /** Someone else's booking and a reference that does not exist are indistinguishable to the caller. */
    public ManageSession openSession(long accountId, String locator) {
        if (!bookings.owns(indexOf(accountId), locator)) {
            metrics.denied();
            log.atInfo().addKeyValue("account.id", accountId).log("Booking not opened: not this account's");
            throw new PnrNotFoundException("No booking matches this reference");
        }
        metrics.opened();
        log.atInfo().addKeyValue("account.id", accountId).addKeyValue("pnr.locator", locator).log("Booking opened from an account");
        return new ManageSession(tokens.issue(locator), manage.find(locator));
    }

    /** A token for an account that no longer exists is refused like a failed sign-in. */
    private String indexOf(long accountId) {
        return accounts.findById(accountId).map(TravellerAccount::getEmailIndex).orElseThrow(AccountLoginFailedException::new);
    }
}
