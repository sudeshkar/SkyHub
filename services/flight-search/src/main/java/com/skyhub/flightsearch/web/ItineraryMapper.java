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

final class ItineraryMapper {

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

    private static FlightLegDto toDto(FlightLeg leg) {
        return new FlightLegDto(leg.flightNumber(), leg.origin(), leg.destination(), leg.departure(), leg.arrival(),
                leg.aircraftType());
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
