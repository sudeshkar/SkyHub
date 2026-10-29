package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;
import java.time.Instant;

/** What the booking service needs to know about one flight on one date. */
public record FlightInfo(Instant departure, Money fare) {
}
