package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.RefundPolicy;
import java.util.Optional;

/**
 * Port: things that happened to bookings that other services care about (ADR-0007, ADR-0019). Calls must be made
 * <em>inside</em> the transaction that made the change, so an event exists exactly when its change does. The use
 * cases never know whether events travel by outbox, Kafka or anything else.
 */
public interface BookingEvents {

    void bookingConfirmed(Pnr pnr, Money total);

    /**
     * The traveller cancelled. {@code decision} is what the fare rules say about the money (ADR-0016) and {@code paid}
     * is what the booking cost, empty for a booking that was never paid. Not called again when cancel is repeated.
     */
    void bookingCancelled(Pnr pnr, RefundPolicy.Decision decision, Optional<Money> paid);

    /** A flight on this booking was delayed or cancelled by the airline. One call per affected booking. */
    void bookingDisrupted(Pnr pnr, Disruption disruption);
}
