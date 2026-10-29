package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.Timetable;

/** Supplies the current timetable snapshot. Services depend on this, not on how it is loaded. */
@FunctionalInterface
public interface TimetableSource {

    Timetable current();
}
