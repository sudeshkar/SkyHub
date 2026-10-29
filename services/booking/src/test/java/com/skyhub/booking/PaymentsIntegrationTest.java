package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.integration.MockPaymentGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Paying for a booking on real PostgreSQL through the real filter chain (ADR-0015). Every flight costs 100.00 USD a
 * passenger (see TestFlightCatalog). Every test uses its own flight date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class PaymentsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockPaymentGateway gateway;

    @Test
    void should_charge_the_server_price_and_record_it_without_any_card_data() throws Exception {
        mvc.perform(book("2027-04-01", "tok_visa", null, UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isCreated());

        var payment = jdbc.queryForMap("SELECT * FROM payment WHERE locator = (SELECT locator FROM pnr ORDER BY id DESC LIMIT 1)");
        assertThat(payment.get("amount")).hasToString("200.00"); // two flights, one passenger
        assertThat(payment.get("currency")).isEqualTo("USD");
        assertThat(payment.get("status")).isEqualTo("CAPTURED");
        assertThat((String) payment.get("charge_id")).startsWith("ch_");
        assertThat(payment.keySet()).noneMatch(column -> column.contains("card") || column.contains("token"));
    }

    @Test
    void should_book_nothing_when_the_card_is_declined() throws Exception {
        long before = pnrs();

        mvc.perform(book("2027-04-02", "tok_declined", null, UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment declined"));

        assertThat(pnrs()).isEqualTo(before);
        assertThat(seatStatus("SK501", "2027-04-02", "11D")).isNotEqualTo("BOOKED");
    }

    @Test
    void should_book_nothing_and_say_retry_when_the_provider_is_down() throws Exception {
        long before = pnrs();

        mvc.perform(book("2027-04-03", "tok_provider_down", null, UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        assertThat(pnrs()).isEqualTo(before);
    }

    @Test
    void should_refuse_a_stale_price_without_charging_and_accept_the_right_one() throws Exception {
        int charges = gateway.chargeCount();

        mvc.perform(book("2027-04-04", "tok_visa", "150.00", UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Price changed"));
        assertThat(gateway.chargeCount()).isEqualTo(charges);

        mvc.perform(book("2027-04-04", "tok_visa", "200.00", UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isCreated());
    }

    @Test
    void should_not_charge_twice_when_the_same_request_is_sent_again() throws Exception {
        String key = UUID.randomUUID().toString();
        int charges = gateway.chargeCount();

        mvc.perform(book("2027-04-05", "tok_visa", null, key, "11D", "24A")).andExpect(status().isCreated());
        mvc.perform(book("2027-04-05", "tok_visa", null, key, "11D", "24A")).andExpect(status().isOk());

        assertThat(gateway.chargeCount()).isEqualTo(charges + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment p JOIN pnr n ON n.locator = p.locator "
                + "JOIN pnr_segment s ON s.pnr_id = n.id WHERE s.departure_date = '2027-04-05'", Integer.class)).isEqualTo(2);
    }

    @Test
    void should_refund_when_the_seat_is_taken_after_paying_and_let_a_retry_pay_afresh() throws Exception {
        mvc.perform(book("2027-04-06", "tok_visa", null, UUID.randomUUID().toString(), "11D", "24A")).andExpect(status().isCreated());
        String key = UUID.randomUUID().toString();
        int charges = gateway.chargeCount();
        int refunds = gateway.refundCount();

        mvc.perform(book("2027-04-06", "tok_visa", null, key, "11D", "25A")) // 11D is already booked
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"));

        assertThat(gateway.chargeCount()).isEqualTo(charges + 1);
        assertThat(gateway.refundCount()).isEqualTo(refunds + 1);
        // The traveller picks another seat and retries with the same key: a new charge, never the refunded one.
        mvc.perform(book("2027-04-06", "tok_visa", null, key, "12D", "25A")).andExpect(status().isCreated());
        assertThat(gateway.chargeCount()).isEqualTo(charges + 2);
    }

    @Test
    void should_reject_a_malformed_payment_token_before_anything_happens() throws Exception {
        int charges = gateway.chargeCount();

        mvc.perform(book("2027-04-07", "4242 4242 4242 4242", null, UUID.randomUUID().toString(), "11D", "24A"))
                .andExpect(status().isBadRequest());

        assertThat(gateway.chargeCount()).isEqualTo(charges);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long pnrs() {
        return jdbc.queryForObject("SELECT count(*) FROM pnr", Long.class);
    }

    private String seatStatus(String flight, String date, String seat) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE flight_number = ? AND departure_date = ?::date AND seat_number = ?",
                String.class, flight, date, seat);
    }

    private static MockHttpServletRequestBuilder book(String date, String token, String expected, String key,
                                                      String firstSeat, String secondSeat) {
        String expectedField = expected == null ? "" : "\"expectedAmount\": " + expected + ",";
        return post("/api/v1/pnrs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "paymentToken": "%s",
                  %s
                  "contactEmail": "priya@example.com",
                  "segments": [
                    {"flightNumber": "SK501", "departureDate": "%s"},
                    {"flightNumber": "SK901", "departureDate": "%s"}
                  ],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s", "%s"]}]
                }
                """.formatted(token, expectedField, date, date, firstSeat, secondSeat));
    }
}
