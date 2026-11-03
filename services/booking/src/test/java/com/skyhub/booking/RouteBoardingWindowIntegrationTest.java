package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A window set for one route (ADR-0077), on real PostgreSQL through the real filter chain. The test flights leave in 5 hours (see
 * TestFlightCatalog) and fly DXB to LHR, except SK610, which flies DXB to JFK. The standard window opens 45 minutes before departure, so
 * every flight is shut for now; the settings below open DXB-LHR six hours before, so only that route boards.
 */
@SpringBootTest(properties = {"skyhub.booking.boarding.routes.DXB-LHR.opens-before=PT6H", "skyhub.booking.boarding.routes.DXB-LHR.closes-before=PT15M"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class RouteBoardingWindowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String checkedInPass(String flight, String date, String seat) throws Exception {
        String locator = JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                         "segments":[{"flightNumber":"%s","departureDate":"%s"}],
                         "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(flight, date, seat)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
        String token = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        return JsonPath.read(mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");
    }

    @Test
    void should_board_on_the_route_that_has_its_own_window_and_refuse_on_a_route_that_does_not() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String onTheRoute = checkedInPass("SK501", "2031-02-01", "22A");     // DXB to LHR: open from 6 hours
        String elsewhere = checkedInPass("SK610", "2031-02-01", "22B");      // DXB to JFK: the standard 45 minutes

        mvc.perform(post("/api/v1/ops/boarding-passes/boardings").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"barcode\":\"%s\"}".formatted(onTheRoute))).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/ops/boarding-passes/boardings").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"barcode\":\"%s\"}".formatted(elsewhere))).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("BOARDING_NOT_OPEN"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger bp JOIN pnr p ON p.locator = bp.locator JOIN pnr_segment s ON s.pnr_id = p.id"
                + " WHERE s.departure_date = '2031-02-01'", Integer.class)).isEqualTo(1);
    }
}
