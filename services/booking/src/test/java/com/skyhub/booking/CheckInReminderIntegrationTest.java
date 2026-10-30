package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.CheckInReminders;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Check-in-open reminders (ADR-0029) through the real store on PostgreSQL. {@link TestFlightCatalog} puts most flights 5 hours
 * away (window open), SK777 30 hours away (not open yet) and SK888 30 minutes away (already closed). The timer is off in tests, so
 * a sweep runs when the test says.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class CheckInReminderIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CheckInReminders reminders;

    private String ops;

    @BeforeEach
    void logIn() throws Exception {
        ops = TestStaff.adminBearer(mvc);
    }

    private boolean reminded(String locator) {
        return jdbc.queryForObject("SELECT count(*) FROM check_in_reminder WHERE locator = ?", Integer.class, locator) == 1;
    }

    @Test
    void should_remind_a_booking_whose_check_in_is_open_exactly_once() throws Exception {
        String locator = book("SK660", "14A");
        long events = outboxEvents();

        reminders.sweep();
        reminders.sweep();

        assertThat(reminded(locator)).isTrue();
        assertThat(outboxEvents() - events).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_reminder WHERE locator = ?", Integer.class, locator)).isEqualTo(1);
    }

    @Test
    void should_not_remind_when_check_in_has_not_opened_or_has_already_closed() throws Exception {
        String early = book(TestFlightCatalog.NOT_YET_OPEN_FLIGHT, "14B");
        String late = book(TestFlightCatalog.CLOSED_FLIGHT, "14C");

        reminders.sweep();

        assertThat(reminded(early)).isFalse();
        assertThat(reminded(late)).isFalse();
    }

    @Test
    void should_not_remind_a_cancelled_booking_or_a_cancelled_flight_or_someone_already_checked_in() throws Exception {
        String cancelledBooking = book("SK661", "14A");
        String cancelledFlight = book("SK662", "14A");
        String checkedIn = book("SK663", "14A");
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", cancelledBooking)
                .header("Authorization", "Bearer " + manageToken(cancelledBooking))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"flightNumber\":\"SK662\",\"departureDate\":\"" + TODAY + "\",\"type\":\"CANCELLED\",\"reason\":\"Test\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pnrs/{l}/check-ins", checkedIn).header("Authorization", "Bearer " + manageToken(checkedIn))
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}")).andExpect(status().isCreated());

        reminders.sweep();

        assertThat(reminded(cancelledBooking)).isFalse();
        assertThat(reminded(cancelledFlight)).isFalse();
        assertThat(reminded(checkedIn)).isFalse();
    }

    @Test
    void should_let_only_one_of_two_racing_sweeps_claim_a_booking() throws Exception {
        String locator = book("SK664", "14A");

        var first = java.util.concurrent.CompletableFuture.runAsync(reminders::sweep);
        var second = java.util.concurrent.CompletableFuture.runAsync(reminders::sweep);
        first.get();
        second.get();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in_reminder WHERE locator = ?", Integer.class, locator)).isEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long outboxEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = 'booking.check-in-open'", Long.class);
    }

    private String manageToken(String locator) throws Exception {
        String body = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String book(String flight, String seat) throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(flight, TODAY, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }
}
