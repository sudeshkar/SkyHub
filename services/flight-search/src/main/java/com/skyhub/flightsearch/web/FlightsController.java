package com.skyhub.flightsearch.web;

import com.skyhub.flightsearch.api.FlightsApi;
import com.skyhub.flightsearch.api.model.FlightLegDto;
import com.skyhub.flightsearch.application.FlightLookupService;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FlightsController implements FlightsApi {

    private final FlightLookupService lookupService;

    public FlightsController(FlightLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public ResponseEntity<FlightLegDto> getDeparture(String flightNumber, LocalDate departureDate) {
        return ResponseEntity.ok()
                .cacheControl(ItineraryMapper.TIMETABLE_CACHING)
                .body(ItineraryMapper.toDto(lookupService.departure(flightNumber, departureDate)));
    }
}
