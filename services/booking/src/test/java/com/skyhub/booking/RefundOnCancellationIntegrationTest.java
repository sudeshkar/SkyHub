package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.integration.MockPaymentGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Refund on cancellation (ADR-0016) on real PostgreSQL through the real filter chain. In TestFlightCatalog SK777
 * leaves in 30 hours (full refund) and every ordinary flight such as SK501 in 5 hours (no refund). One passenger on
 * one flight costs 100.00 USD. Every test uses its own date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class RefundOnCancellationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private MockPaymentGateway gateway;

    @Test
    void should_give_the_money_back_when_cancelling_well_before_departure() throws Exception {
        String locator = book("SK777", "2027-05-01", "11D");
        int refunds = gateway.refundCount();

        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.amount").value(100.00))
                .andExpect(jsonPath("$.refund.currency").value("USD"));

        assertThat(paymentStatus(locator)).isEqualTo("REFUNDED");
        assertThat(gateway.refundCount()).isEqualTo(refunds + 1);
        assertThat(seatStatus("SK777", "2027-05-01", "11D")).isEqualTo("AVAILABLE");
    }

    @Test
    void should_refuse_a_refund_when_cancelling_too_close_to_departure_but_still_free_the_seat() throws Exception {
        String locator = book("SK501", "2027-05-02", "11D");
        int refunds = gateway.refundCount();

        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("NOT_REFUNDABLE"))
                .andExpect(jsonPath("$.refund.amount").value(100.00));

        assertThat(paymentStatus(locator)).isEqualTo("NO_REFUND");
        assertThat(gateway.refundCount()).isEqualTo(refunds);
        assertThat(seatStatus("SK501", "2027-05-02", "11D")).isEqualTo("AVAILABLE");
    }

    @Test
    void should_refund_in_full_at_any_time_when_the_airline_cancelled_the_flight() throws Exception {
        String locator = book("SK501", "2027-05-03", "11D");
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", TestStaff.adminBearer(mvc)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-05-03\","
                                + "\"type\":\"CANCELLED\",\"reason\":\"Aircraft grounded\"}"))
                .andExpect(status().isOk());

        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"));

        assertThat(paymentStatus(locator)).isEqualTo("REFUNDED");
    }

    @Test
    void should_cancel_anyway_when_the_provider_is_down_and_pay_on_the_next_try() throws Exception {
        String locator = book("SK777", "2027-05-04", "11D");
        doThrow(new PaymentUnavailableException("The payment provider did not answer", null))
                .doCallRealMethod().when(gateway).refund(anyString());

        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("PENDING"));
        assertThat(paymentStatus(locator)).isEqualTo("REFUND_DUE");
        assertThat(seatStatus("SK777", "2027-05-04", "11D")).isEqualTo("AVAILABLE");

        // The page shows it as pending without moving any money.
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", tokenFor(locator)))
                .andExpect(jsonPath("$.refund.status").value("PENDING"));
        assertThat(paymentStatus(locator)).isEqualTo("REFUND_DUE");

        // Cancelling again is how a pending refund is retried.
        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"));
        assertThat(paymentStatus(locator)).isEqualTo("REFUNDED");
    }

    @Test
    void should_never_refund_twice() throws Exception {
        String locator = book("SK777", "2027-05-05", "11D");
        mvc.perform(cancel(locator)).andExpect(status().isOk());
        int refunds = gateway.refundCount();

        mvc.perform(cancel(locator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"));

        assertThat(gateway.refundCount()).isEqualTo(refunds);
    }

    @Test
    void should_show_no_refund_on_a_booking_that_is_still_active() throws Exception {
        String locator = book("SK777", "2027-05-06", "11D");

        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", tokenFor(locator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund").doesNotExist());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancel(String locator)
            throws Exception {
        return post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator));
    }

    private String paymentStatus(String locator) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE locator = ?", String.class, locator);
    }

    private String seatStatus(String flight, String date, String seat) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE flight_number = ? AND departure_date = ?::date AND seat_number = ?",
                String.class, flight, date, seat);
    }

    private String book(String flight, String date, String seat) throws Exception {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "priya@example.com",
                  "segments": [{"flightNumber": "%s", "departureDate": "%s"}],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s"]}]
                }
                """.formatted(flight, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }
}
