package com.skyhub.flightsearch.web;

import com.skyhub.flightsearch.api.model.ConnectionDto;
import com.skyhub.flightsearch.api.model.FlightLegDto;
import com.skyhub.flightsearch.api.model.HubDto;
import com.skyhub.flightsearch.api.model.ItineraryDto;
import com.skyhub.flightsearch.domain.Connection;
import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.Hub;
import com.skyhub.flightsearch.domain.Itinerary;
import java.time.Duration;
import org.springframework.http.CacheControl;

final class ItineraryMapper {

    /** Timetable data changes rarely; browsers and CDNs may reuse it for a minute. Never used for seat stock. */
    static final CacheControl TIMETABLE_CACHING = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private ItineraryMapper() {
    }

    static ItineraryDto toDto(Itinerary itinerary) {
        return new ItineraryDto(
                itinerary.legs().stream().map(ItineraryMapper::toDto).toList(),
                itinerary.connections().stream().map(ItineraryMapper::toDto).toList(),
                itinerary.stops(),
                minutes(itinerary.totalDuration()),
                itinerary.totalFare(),
                itinerary.currency());
    }

    static HubDto toDto(Hub hub) {
        return new HubDto(hub.code(), hub.name(), hub.active());
    }

    static FlightLegDto toDto(FlightLeg leg) {
        return new FlightLegDto(leg.flightNumber(), leg.origin(), leg.destination(), leg.departure(), leg.arrival(),
                leg.aircraftType(), leg.fare(), leg.currency());
    }

    private static ConnectionDto toDto(Connection connection) {
        return new ConnectionDto(connection.arrivalAirport(), connection.departureAirport(),
                minutes(connection.layover()), connection.requiresAirportTransfer(),
                minutes(connection.transferTime()));
    }

    private static int minutes(Duration duration) {
        return Math.toIntExact(duration.toMinutes());
    }
}
