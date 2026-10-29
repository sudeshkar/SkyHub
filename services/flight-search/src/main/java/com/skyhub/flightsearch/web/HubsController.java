package com.skyhub.flightsearch.web;

import com.skyhub.flightsearch.api.HubsApi;
import com.skyhub.flightsearch.api.model.HubDto;
import com.skyhub.flightsearch.domain.HubNetwork;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HubsController implements HubsApi {

    private final HubNetwork hubNetwork;

    public HubsController(HubNetwork hubNetwork) {
        this.hubNetwork = hubNetwork;
    }

    @Override
    public ResponseEntity<List<HubDto>> listHubs() {
        return ResponseEntity.ok(hubNetwork.hubs().stream().map(ItineraryMapper::toDto).toList());
    }
}
