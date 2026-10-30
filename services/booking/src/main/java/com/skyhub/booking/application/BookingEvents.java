package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;

/**
 * Port: things that happened to bookings that other services care about (ADR-0007, ADR-0019). Calls must be made
 * <em>inside</em> the transaction that made the change, so an event exists exactly when its change does. The use
 * cases never know whether events travel by outbox, Kafka or anything else.
 */
public interface BookingEvents {

    void bookingConfirmed(Pnr pnr, Money total);
}
