package com.skyhub.flightsearch.domain;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for hub rules. No other class should mention an airport code.
 * See docs/adr/0001-multi-hub-configuration.md.
 */
public final class HubNetwork {

    private final Map<String, Hub> hubs = new LinkedHashMap<>();
    private final Map<String, Map<String, Duration>> transfers = new HashMap<>();
    private final Duration maxLayover;

    public HubNetwork(List<Hub> hubs, List<AirportTransfer> transfers, Duration maxLayover) {
        for (Hub hub : hubs) {
            if (this.hubs.putIfAbsent(hub.code(), hub) != null) {
                throw new IllegalArgumentException("Duplicate hub " + hub.code());
            }
        }
        for (AirportTransfer transfer : transfers) {
            requireHub(transfer.from());
            requireHub(transfer.to());
            this.transfers.computeIfAbsent(transfer.from(), k -> new HashMap<>()).put(transfer.to(), transfer.duration());
            this.transfers.computeIfAbsent(transfer.to(), k -> new HashMap<>()).put(transfer.from(), transfer.duration());
        }
        this.maxLayover = AirportCodes.requirePositive(maxLayover, "Maximum layover");
    }

    public List<Hub> hubs() {
        return List.copyOf(hubs.values());
    }

    public boolean isActiveHub(String airport) {
        Hub hub = hubs.get(airport);
        return hub != null && hub.active();
    }

    /** Airports a passenger arriving at {@code arrivalAirport} can depart from: the hub itself and active partners. */
    public Set<String> onwardAirports(String arrivalAirport) {
        if (!isActiveHub(arrivalAirport)) {
            return Set.of();
        }
        Set<String> airports = new LinkedHashSet<>();
        airports.add(arrivalAirport);
        transfers.getOrDefault(arrivalAirport, Map.of()).keySet().stream()
                .filter(this::isActiveHub)
                .sorted()
                .forEach(airports::add);
        return airports;
    }

    public Duration transferTime(String from, String to) {
        if (from.equals(to)) {
            return Duration.ZERO;
        }
        Duration duration = transfers.getOrDefault(from, Map.of()).get(to);
        if (duration == null) {
            throw new IllegalArgumentException("No ground transfer between " + from + " and " + to);
        }
        return duration;
    }

    /** Same airport: that hub's MCT. Different airports: transfer time plus the departure hub's MCT. */
    public Duration requiredConnectionTime(String arrivalAirport, String departureAirport) {
        return transferTime(arrivalAirport, departureAirport).plus(requireHub(departureAirport).minimumConnectionTime());
    }

    public Duration maxLayover() {
        return maxLayover;
    }

    private Hub requireHub(String code) {
        Hub hub = hubs.get(code);
        if (hub == null) {
            throw new IllegalArgumentException("Unknown hub " + code);
        }
        return hub;
    }
}
