package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.Connection;
import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.FlightSchedule;
import com.skyhub.flightsearch.domain.FlightStatus;
import com.skyhub.flightsearch.domain.HubNetwork;
import com.skyhub.flightsearch.domain.InvalidSearchException;
import com.skyhub.flightsearch.domain.Itinerary;
import com.skyhub.flightsearch.domain.Timetable;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Finds direct and one-stop itineraries from the in-memory timetable: no database access per search.
 * Algorithm: docs/design/02-lld.md §3.4, with onward flights grouped by departure airport (a hash
 * join), so each inbound flight finds its candidates in O(1) instead of scanning every onward flight.
 */
@Service
public class ItinerarySearchService {

    private static final Comparator<Itinerary> BY_DEPARTURE_THEN_DURATION =
            Comparator.comparing(Itinerary::departure).thenComparing(Itinerary::totalDuration);

    private final TimetableSource timetables;
    private final HubNetwork hubNetwork;
    private final FlightStatusSource statuses;

    public ItinerarySearchService(TimetableSource timetables, HubNetwork hubNetwork, FlightStatusSource statuses) {
        this.timetables = timetables;
        this.hubNetwork = hubNetwork;
        this.statuses = statuses;
    }

    public List<Itinerary> search(String origin, String destination, LocalDate date) {
        if (origin.equals(destination)) {
            throw new InvalidSearchException("Origin and destination must differ: " + origin);
        }
        Timetable timetable = timetables.current();
        List<FlightSchedule> fromOrigin = timetable.departingFrom(origin);
        Map<String, List<FlightSchedule>> onwardByOrigin = timetable.arrivingAt(destination).stream()
                .collect(Collectors.groupingBy(FlightSchedule::getOrigin));

        List<Itinerary> results = new ArrayList<>();
        for (FlightSchedule schedule : fromOrigin) {
            if (schedule.getDestination().equals(destination) && schedule.operatesOn(date)) {
                operatingLeg(schedule, date).ifPresent(leg -> results.add(Itinerary.direct(leg)));
            }
        }
        for (FlightSchedule first : fromOrigin) {
            if (first.operatesOn(date) && hubNetwork.isActiveHub(first.getDestination())) {
                operatingLeg(first, date).ifPresent(inbound -> results.addAll(connectionsFrom(inbound, onwardByOrigin)));
            }
        }
        results.sort(BY_DEPARTURE_THEN_DURATION);
        return List.copyOf(results);
    }

    /**
     * The dated flight with what is known about it, or nothing if it is cancelled: a cancelled flight is not offered. A delay is
     * shown beside the schedule, and connections are judged on the expected times (ADR-0027).
     */
    private Optional<FlightLeg> operatingLeg(FlightSchedule schedule, LocalDate date) {
        FlightLeg leg = schedule.legOn(date);
        FlightStatus status = statuses.statusOf(leg.flightNumber(), date);
        return status.isCancelled() ? Optional.empty() : Optional.of(leg.withStatus(status));
    }

    private List<Itinerary> connectionsFrom(FlightLeg inbound, Map<String, List<FlightSchedule>> onwardByOrigin) {
        List<Itinerary> itineraries = new ArrayList<>();
        LocalDate arrivalDate = inbound.arrival().toLocalDate();
        for (String departureAirport : hubNetwork.onwardAirports(inbound.destination())) {
            Duration required = hubNetwork.requiredConnectionTime(inbound.destination(), departureAirport);
            for (FlightSchedule second : onwardByOrigin.getOrDefault(departureAirport, List.of())) {
                // Check the arrival day and the next one, for overnight connections.
                for (LocalDate day : List.of(arrivalDate, arrivalDate.plusDays(1))) {
                    if (!second.operatesOn(day)) {
                        continue;
                    }
                    FlightLeg outbound = operatingLeg(second, day).orElse(null);
                    if (outbound == null) {
                        continue;   // that onward flight is cancelled
                    }
                    Duration layover = Duration.between(inbound.expectedArrival(), outbound.expectedDeparture());
                    if (layover.compareTo(required) >= 0 && layover.compareTo(hubNetwork.maxLayover()) <= 0) {
                        Connection connection = new Connection(inbound.destination(), departureAirport, layover,
                                hubNetwork.transferTime(inbound.destination(), departureAirport));
                        itineraries.add(Itinerary.oneStop(inbound, outbound, connection));
                    }
                }
            }
        }
        return itineraries;
    }
}
