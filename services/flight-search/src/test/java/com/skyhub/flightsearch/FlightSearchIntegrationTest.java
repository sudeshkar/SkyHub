package com.skyhub.flightsearch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Full stack against real PostgreSQL, using the seed timetable (docs/design/02-lld.md §7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class FlightSearchIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void should_connect_cmb_to_jfk_with_dxb_to_dwc_transfer() throws Exception {
        mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "JFK")
                        .param("date", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].legs[0].flightNumber").value("SK501"))
                .andExpect(jsonPath("$[0].legs[1].flightNumber").value("SK901"))
                .andExpect(jsonPath("$[0].connections[0].arrivalAirport").value("DXB"))
                .andExpect(jsonPath("$[0].connections[0].departureAirport").value("DWC"))
                .andExpect(jsonPath("$[0].connections[0].requiresAirportTransfer").value(true))
                .andExpect(jsonPath("$[0].connections[0].layoverMinutes").value(220));
    }

    @Test
    void should_offer_same_hub_and_cross_airport_options_from_bom_to_jfk() throws Exception {
        mvc.perform(get("/api/v1/itineraries").param("origin", "BOM").param("destination", "JFK")
                        .param("date", "2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].legs[0].flightNumber").value("SK201"))
                .andExpect(jsonPath("$[0].connections[0].requiresAirportTransfer").value(true))
                .andExpect(jsonPath("$[1].legs[0].flightNumber").value("SK203"))
                .andExpect(jsonPath("$[1].connections[0].requiresAirportTransfer").value(false));
    }

    @Test
    void should_list_configured_hubs() throws Exception {
        mvc.perform(get("/api/v1/hubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("DXB"))
                .andExpect(jsonPath("$[1].code").value("DWC"));
    }

    @Test
    void should_confirm_a_flight_that_operates_and_allow_caching() throws Exception {
        mvc.perform(get("/api/v1/flights/SK411/departures/2026-11-02"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.origin").value("DWC"))
                .andExpect(jsonPath("$.departureTime").value("2026-11-02T11:00:00Z"));
    }

    @Test
    void should_return_404_for_a_flight_that_does_not_operate() throws Exception {
        mvc.perform(get("/api/v1/flights/SK411/departures/2026-11-03")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flights/SK999/departures/2026-11-02")).andExpect(status().isNotFound());
    }
}
