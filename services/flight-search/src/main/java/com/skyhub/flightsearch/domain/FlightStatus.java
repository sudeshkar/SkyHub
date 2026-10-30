package com.skyhub.flightsearch.domain;

import java.time.OffsetDateTime;

/**
 * What is known about one dated flight beyond its timetable (ADR-0025): on time, delayed to a new time, or cancelled. The
 * scheduled time of a flight never changes; a delay is reported beside it.
 */
public record FlightStatus(Kind kind, OffsetDateTime revisedDeparture) {

    public enum Kind { SCHEDULED, DELAYED, CANCELLED }

    public static final FlightStatus SCHEDULED = new FlightStatus(Kind.SCHEDULED, null);

    public static FlightStatus delayedTo(OffsetDateTime revisedDeparture) {
        return new FlightStatus(Kind.DELAYED, revisedDeparture);
    }

    public static FlightStatus cancelled() {
        return new FlightStatus(Kind.CANCELLED, null);
    }

    public boolean isCancelled() {
        return kind == Kind.CANCELLED;
    }
}
