package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.Connection;
import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.FlightSchedule;
import com.skyhub.flightsearch.domain.HubNetwork;
import com.skyhub.flightsearch.domain.InvalidSearchException;
import com.skyhub.flightsearch.domain.Itinerary;
import com.skyhub.flightsearch.persistence.FlightScheduleRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds direct and one-stop itineraries. Algorithm: docs/design/02-lld.md §3.4. */
@Service
public class ItinerarySearchService {

    private static final Comparator<Itinerary> BY_DEPARTURE_THEN_DURATION =
            Comparator.comparing(Itinerary::departure).thenComparing(Itinerary::totalDuration);

    private final FlightScheduleRepository schedules;
    private final HubNetwork hubNetwork;

    public ItinerarySearchService(FlightScheduleRepository schedules, HubNetwork hubNetwork) {
        this.schedules = schedules;
        this.hubNetwork = hubNetwork;
    }

    @Cacheable(cacheNames = "itineraries", key = "#origin + ':' + #destination + ':' + #date")
    @Transactional(readOnly = true)
    public List<Itinerary> search(String origin, String destination, LocalDate date) {
        if (origin.equals(destination)) {
            throw new InvalidSearchException("Origin and destination must differ: " + origin);
        }
        List<FlightSchedule> fromOrigin = schedules.findByOrigin(origin);
        List<FlightSchedule> intoDestination = schedules.findByDestination(destination);

        List<Itinerary> results = new ArrayList<>();
        for (FlightSchedule schedule : fromOrigin) {
            if (schedule.getDestination().equals(destination) && schedule.operatesOn(date)) {
                results.add(Itinerary.direct(schedule.legOn(date)));
            }
        }
        for (FlightSchedule first : fromOrigin) {
            if (first.operatesOn(date) && hubNetwork.isActiveHub(first.getDestination())) {
                results.addAll(connectionsFrom(first.legOn(date), intoDestination));
            }
        }
        results.sort(BY_DEPARTURE_THEN_DURATION);
        return List.copyOf(results);
    }

    private List<Itinerary> connectionsFrom(FlightLeg inbound, List<FlightSchedule> intoDestination) {
        List<Itinerary> itineraries = new ArrayList<>();
        LocalDate arrivalDate = inbound.arrival().toLocalDate();
        for (String departureAirport : hubNetwork.onwardAirports(inbound.destination())) {
            Duration required = hubNetwork.requiredConnectionTime(inbound.destination(), departureAirport);
            for (FlightSchedule second : intoDestination) {
                if (!second.getOrigin().equals(departureAirport)) {
                    continue;
                }
                // Check the arrival day and the next one, for overnight connections.
                for (LocalDate day : List.of(arrivalDate, arrivalDate.plusDays(1))) {
                    if (!second.operatesOn(day)) {
                        continue;
                    }
                    FlightLeg outbound = second.legOn(day);
                    Duration layover = Duration.between(inbound.arrival(), outbound.departure());
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
