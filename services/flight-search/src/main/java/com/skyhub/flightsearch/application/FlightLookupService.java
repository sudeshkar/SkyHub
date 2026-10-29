package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.FlightNotFoundException;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** Answers "does this flight operate on this date?" from the in-memory timetable: O(1), no database. */
@Service
public class FlightLookupService {

    private final TimetableSource timetables;

    public FlightLookupService(TimetableSource timetables) {
        this.timetables = timetables;
    }

    public FlightLeg departure(String flightNumber, LocalDate date) {
        return timetables.current().flight(flightNumber)
                .filter(schedule -> schedule.operatesOn(date))
                .map(schedule -> schedule.legOn(date))
                .orElseThrow(() -> new FlightNotFoundException(flightNumber + " does not operate on " + date));
    }
}
