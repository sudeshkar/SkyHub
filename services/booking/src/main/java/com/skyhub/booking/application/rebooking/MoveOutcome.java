package com.skyhub.booking.application.rebooking;

/** What became of one move in a bulk request (ADR-0035). */
public enum MoveOutcome {
    MOVED,
    /** The booking was already on that flight. */
    UNCHANGED,
    /** A rule forbids the move; the detail says which. */
    REFUSED,
    NOT_FOUND,
    /** The seats changed while moving; asking again is safe and usually works. */
    CONFLICT,
    /** The timetable did not answer; the rest of the request was not tried. */
    UNAVAILABLE,
    /** Something unexpected failed; the rest of the request was not tried. */
    FAILED,
    /** Not tried, because an earlier move showed the system is not answering. */
    NOT_ATTEMPTED
}
