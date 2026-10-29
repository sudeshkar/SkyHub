package com.skyhub.flightsearch.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

public record Itinerary(List<FlightLeg> legs, List<Connection> connections) {

    public Itinerary {
        legs = List.copyOf(legs);
        connections = List.copyOf(connections);
        if (legs.isEmpty() || connections.size() != legs.size() - 1) {
            throw new IllegalArgumentException("An itinerary needs n legs and n-1 connections");
        }
        String currency = legs.getFirst().currency();
        if (legs.stream().anyMatch(leg -> !leg.currency().equals(currency))) {
            throw new IllegalArgumentException("All legs must be priced in one currency");
        }
    }

    public static Itinerary direct(FlightLeg leg) {
        return new Itinerary(List.of(leg), List.of());
    }

    public static Itinerary oneStop(FlightLeg inbound, FlightLeg outbound, Connection connection) {
        return new Itinerary(List.of(inbound, outbound), List.of(connection));
    }

    public int stops() {
        return connections.size();
    }

    public OffsetDateTime departure() {
        return legs.getFirst().departure();
    }

    public OffsetDateTime arrival() {
        return legs.getLast().arrival();
    }

    public Duration totalDuration() {
        return Duration.between(departure(), arrival());
    }

    public BigDecimal totalFare() {
        return legs.stream().map(FlightLeg::fare).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String currency() {
        return legs.getFirst().currency();
    }
}
