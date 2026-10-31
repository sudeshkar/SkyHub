package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.domain.Disruption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When a flight is expected to leave and land, given any recorded delay: the revised departure, and the scheduled arrival moved by
 * the same amount (a delay is assumed to last the whole flight, as in search, ADR-0027). Scheduled times are never changed.
 */
final class ExpectedTimes {

    private ExpectedTimes() {
    }

    static Instant departure(FlightInfo flight, Optional<Disruption> disruption) {
        return delay(flight, disruption).map(flight.departure()::plus).orElse(flight.departure());
    }

    /** Empty when the flight's scheduled arrival is not known. */
    static Optional<Instant> arrival(FlightInfo flight, Optional<Disruption> disruption) {
        return Optional.ofNullable(flight.arrival()).map(arrival -> delay(flight, disruption).map(arrival::plus).orElse(arrival));
    }

    private static Optional<Duration> delay(FlightInfo flight, Optional<Disruption> disruption) {
        return disruption.filter(d -> d.getRevisedDeparture() != null && !d.isCancelled())
                .map(d -> Duration.between(flight.departure(), d.getRevisedDeparture()));
    }
}
