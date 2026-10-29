package com.skyhub.flightsearch.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.flightsearch.application.ItinerarySearchService;
import com.skyhub.flightsearch.config.SecurityConfig;
import com.skyhub.flightsearch.domain.Connection;
import com.skyhub.flightsearch.domain.FlightLeg;
import com.skyhub.flightsearch.domain.InvalidSearchException;
import com.skyhub.flightsearch.domain.Itinerary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ItinerariesController.class)
@Import(SecurityConfig.class)
class ItinerariesControllerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ItinerarySearchService searchService;

    @Test
    void should_return_itineraries_in_contract_shape() throws Exception {
        when(searchService.search("CMB", "JFK", MONDAY)).thenReturn(List.of(cmbToJfkViaTransfer()));

        mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "JFK")
                        .param("date", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].stops").value(1))
                .andExpect(jsonPath("$[0].totalDurationMinutes").value(1350))
                .andExpect(jsonPath("$[0].fareFrom").value(1240.00))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].legs[0].flightNumber").value("SK501"))
                .andExpect(jsonPath("$[0].legs[0].departureTime").value("2026-11-02T02:00:00Z"))
                .andExpect(jsonPath("$[0].connections[0].requiresAirportTransfer").value(true))
                .andExpect(jsonPath("$[0].connections[0].layoverMinutes").value(220))
                .andExpect(jsonPath("$[0].connections[0].transferMinutes").value(120));
    }

    @Test
    void should_reject_invalid_airport_code_with_problem_detail() throws Exception {
        mvc.perform(get("/api/v1/itineraries").param("origin", "cm").param("destination", "JFK")
                        .param("date", "2026-11-02"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void should_reject_missing_date_with_problem_detail() throws Exception {
        mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "JFK"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void should_reject_same_origin_and_destination_with_problem_detail() throws Exception {
        when(searchService.search(any(), any(), any()))
                .thenThrow(new InvalidSearchException("Origin and destination must differ: CMB"));

        mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "CMB")
                        .param("date", "2026-11-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid search"))
                .andExpect(jsonPath("$.detail").value("Origin and destination must differ: CMB"));
    }

    private static Itinerary cmbToJfkViaTransfer() {
        var inbound = new FlightLeg("SK501", "CMB", "DXB", utc(2, 2, 0), utc(2, 6, 50), "A350",
                new BigDecimal("310.00"), "USD");
        var outbound = new FlightLeg("SK901", "DWC", "JFK", utc(2, 10, 30), utc(3, 0, 30), "B777",
                new BigDecimal("930.00"), "USD");
        return Itinerary.oneStop(inbound, outbound,
                new Connection("DXB", "DWC", Duration.ofMinutes(220), Duration.ofMinutes(120)));
    }

    private static OffsetDateTime utc(int day, int hour, int minute) {
        return OffsetDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
