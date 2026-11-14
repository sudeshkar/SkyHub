package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.RefundPolicy;
import java.util.Optional;

/**
 * Port: things that happened to bookings that other services care about (ADR-0007, ADR-0019). Calls must be made
 * <em>inside</em> the transaction that made the change, so an event exists exactly when its change does. The use
 * cases never know whether events travel by outbox, Kafka or anything else.
 */
public interface BookingEvents {

    /**
     * @param flights what the flight catalog said about each flight of the booking when it was priced, in segment order: the
     *                confirmation describes exactly the trip that was charged for (ADR-0087)
     */
    void bookingConfirmed(Pnr pnr, Money total, java.util.List<FlightInfo> flights);

    /**
     * The traveller cancelled. {@code decision} is what the fare rules say about the money (ADR-0016) and {@code paid}
     * is what the booking cost, empty for a booking that was never paid. Not called again when cancel is repeated.
     */
    void bookingCancelled(Pnr pnr, RefundPolicy.Decision decision, Optional<Money> paid);

    /**
     * Staff moved one flight of this booking onto another (ADR-0023). Called only for a move that changed something.
     * {@code checkedOut} is true when the move also checked the party out of the flight they left (ADR-0069), so the email can tell them
     * to check in again (ADR-0070). {@code fromInfo} and {@code toInfo} are what the flight catalog said about the flight left and the
     * one joined, so the email can show the new route and times (ADR-0088).
     */
    void bookingRebooked(Pnr pnr, PnrSegment from, PnrSegment to, boolean checkedOut, FlightInfo fromInfo, FlightInfo toInfo);

    /** A flight on this booking was delayed or cancelled by the airline. One call per affected booking. */
    void bookingDisrupted(Pnr pnr, Disruption disruption);

    /** Staff withdrew the disruption (ADR-0028). One call per booking still active on the flight. */
    void bookingDisruptionWithdrawn(Pnr pnr, Disruption withdrawn);

    /** Check-in has opened for one flight of this booking (ADR-0029). Called at most once per booking and flight. */
    /** A refund that had been pending was paid by the retry job (ADR-0031). Not called for a refund paid at once. */
    void refundCompleted(Pnr pnr, Money amount);

    void checkInOpened(Pnr pnr, PnrSegment segment, java.time.Instant departure, java.time.Instant closes);

    /**
     * These passengers have just been checked in (ADR-0073): {@code passes} holds only the passes this check-in issued, not anyone who was
     * already in. {@code departure} is the expected time and {@code closes} is when check-in stops. Not called for a repeat. Each pass is
     * signed and is a credential for boarding, so an implementation must put it in the event sealed and never in the clear (ADR-0074).
     */
    void checkedIn(Pnr pnr, PnrSegment segment, java.util.List<BoardingPass> passes, java.time.Instant departure, java.time.Instant closes);
}
