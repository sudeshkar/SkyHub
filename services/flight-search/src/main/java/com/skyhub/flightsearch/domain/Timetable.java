package com.skyhub.flightsearch.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable in-memory index of the whole timetable: O(1) lookups by origin, destination and flight
 * number. A few thousand schedules fit easily in memory, so searches never touch the database.
 * Built once per refresh and shared read-only across threads.
 */
public final class Timetable {

    private final Map<String, List<FlightSchedule>> byOrigin;
    private final Map<String, List<FlightSchedule>> byDestination;
    private final Map<String, FlightSchedule> byFlightNumber;

    private Timetable(Collection<FlightSchedule> schedules) {
        this.byOrigin = group(schedules, FlightSchedule::getOrigin);
        this.byDestination = group(schedules, FlightSchedule::getDestination);
        this.byFlightNumber = schedules.stream()
                .collect(Collectors.toUnmodifiableMap(FlightSchedule::getFlightNumber, Function.identity()));
    }

    public static Timetable of(Collection<FlightSchedule> schedules) {
        return new Timetable(schedules);
    }

    private static Map<String, List<FlightSchedule>> group(Collection<FlightSchedule> schedules,
                                                          Function<FlightSchedule, String> key) {
        return schedules.stream().collect(Collectors.collectingAndThen(
                Collectors.groupingBy(key, Collectors.toUnmodifiableList()), Map::copyOf));
    }

    public List<FlightSchedule> departingFrom(String airport) {
        return byOrigin.getOrDefault(airport, List.of());
    }

    public List<FlightSchedule> arrivingAt(String airport) {
        return byDestination.getOrDefault(airport, List.of());
    }

    public Optional<FlightSchedule> flight(String flightNumber) {
        return Optional.ofNullable(byFlightNumber.get(flightNumber));
    }

    public int size() {
        return byFlightNumber.size();
    }
}
