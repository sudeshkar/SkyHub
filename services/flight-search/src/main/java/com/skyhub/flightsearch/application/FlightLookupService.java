package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.FlightNotFoundException;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** Answers "does this flight operate on this date?" from the in-memory timetable: O(1), no database. */
@Service
public class FlightLookupService {

    private final TimetableSource timetables;
    private final FlightStatusSource statuses;

    public FlightLookupService(TimetableSource timetables, FlightStatusSource statuses) {
        this.timetables = timetables;
        this.statuses = statuses;
    }

    public FlightLeg departure(String flightNumber, LocalDate date) {
        return timetables.current().flight(flightNumber)
                .filter(schedule -> schedule.operatesOn(date))
                .map(schedule -> schedule.legOn(date).withStatus(statuses.statusOf(flightNumber, date)))   // cancelled flights are still found: bookings on them need to see them
                .orElseThrow(() -> new FlightNotFoundException(flightNumber + " does not operate on " + date));
    }
}
