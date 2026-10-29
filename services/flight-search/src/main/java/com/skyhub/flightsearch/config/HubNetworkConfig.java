package com.skyhub.flightsearch.config;

import com.skyhub.flightsearch.domain.AirportTransfer;
import com.skyhub.flightsearch.domain.Hub;
import com.skyhub.flightsearch.domain.HubNetwork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HubNetworkConfig {

    @Bean
    HubNetwork hubNetwork(HubProperties properties) {
        return new HubNetwork(
                properties.hubs().stream()
                        .map(h -> new Hub(h.code(), h.name(), h.active(), h.minimumConnectionTime()))
                        .toList(),
                properties.airportTransfers().stream()
                        .map(t -> new AirportTransfer(t.from(), t.to(), t.duration()))
                        .toList(),
                properties.maxLayover());
    }
}
