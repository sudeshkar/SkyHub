package com.skyhub.flightsearch.web;

import com.skyhub.flightsearch.api.ItinerariesApi;
import com.skyhub.flightsearch.api.model.ItineraryDto;
import com.skyhub.flightsearch.application.ItinerarySearchService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ItinerariesController implements ItinerariesApi {

    private final ItinerarySearchService searchService;

    public ItinerariesController(ItinerarySearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public ResponseEntity<List<ItineraryDto>> searchItineraries(String origin, String destination, LocalDate date) {
        return ResponseEntity.ok(searchService.search(origin, destination, date).stream()
                .map(ItineraryMapper::toDto)
                .toList());
    }
}
