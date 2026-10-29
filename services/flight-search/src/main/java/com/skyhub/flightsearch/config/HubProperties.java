package com.skyhub.flightsearch.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds {@code skyhub.*}. Validation lives in the domain types it maps to, so bad config fails at startup. */
@ConfigurationProperties("skyhub")
public record HubProperties(
        @DefaultValue List<HubEntry> hubs,
        @DefaultValue List<TransferEntry> airportTransfers,
        @DefaultValue("12h") Duration maxLayover) {

    public record HubEntry(String code, String name, @DefaultValue("true") boolean active,
                           Duration minimumConnectionTime) {
    }

    public record TransferEntry(String from, String to, Duration duration) {
    }
}
