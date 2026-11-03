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
 * The boarding window with its real defaults (ADR-0063), on real PostgreSQL through the real filter chain. The test flights leave in
 * 5 hours (see TestFlightCatalog), and boarding opens 45 minutes before departure, so a good pass is refused for now.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class BoardingWindowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void should_refuse_boarding_a_good_pass_before_boarding_opens_but_still_let_it_be_checked() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                         "segments":[{"flightNumber":"SK501","departureDate":"2029-09-01"}],
                         "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["16D"]}]}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
        String token = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String barcode = JsonPath.read(mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");
        String body = "{\"barcode\":\"%s\"}".formatted(barcode);

        mvc.perform(post("/api/v1/ops/boarding-passes/boardings").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("BOARDING_NOT_OPEN")).andExpect(jsonPath("$.pass").doesNotExist());
        mvc.perform(post("/api/v1/ops/boarding-passes/verification").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));   // checking has no clock

        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger WHERE locator = ?", Integer.class, locator)).isZero();
    }
}
