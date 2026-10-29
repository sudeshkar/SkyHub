package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BookingResult;
import com.skyhub.booking.application.BookingService;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.application.PricingService;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PaymentDeclinedException;
import com.skyhub.booking.domain.PriceChangedException;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PnrsController.class)
@Import(WebSliceSecurity.class)
class PnrsControllerTest {

    private static final String BODY = """
            {
              "paymentToken": "tok_visa",
              "contactEmail": "priya@example.com",
              "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}],
              "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["11D"]}]
            }
            """;

    private static final PnrView PNR = new PnrView("ABC234", PnrStatus.CONFIRMED, "p****@example.com",
            List.of(new PnrView.SegmentView("SK501", LocalDate.of(2026, 11, 2))),
            List.of(new PnrView.PassengerView("Priya", "Perera", List.of("11D"))),
            Instant.parse("2026-10-29T10:00:00Z"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private PricingService pricingService;

    @Test
    void should_create_pnr_with_location_header() throws Exception {
        when(bookingService.createPnr(any(), eq("key-12345678"))).thenReturn(new BookingResult(PNR, true));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pnrs/ABC234"))
                .andExpect(jsonPath("$.locator").value("ABC234"))
                .andExpect(jsonPath("$.contactEmail").value("p****@example.com"))
                .andExpect(jsonPath("$.passengers[0].seats[0]").value("11D"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-29T10:00:00Z"));
    }

    @Test
    void should_quote_the_server_side_price() throws Exception {
        when(pricingService.quote(any(), eq(2))).thenReturn(Money.of("861.00", "USD"));

        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}], "passengerCount": 2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(861.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void should_reject_a_quote_for_no_passengers_or_too_many() throws Exception {
        for (int count : new int[] {0, 10}) {
            mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(
                            "{\"segments\": [{\"flightNumber\": \"SK501\", \"departureDate\": \"2026-11-02\"}], \"passengerCount\": "
                                    + count + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(pricingService);
    }

    @Test
    void should_answer_402_when_the_payment_is_declined() throws Exception {
        when(bookingService.createPnr(any(), any())).thenThrow(new PaymentDeclinedException("Your card was declined"));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment declined"))
                .andExpect(jsonPath("$.detail").value("Your card was declined"));
    }

    @Test
    void should_answer_503_with_retry_after_when_the_provider_is_down() throws Exception {
        when(bookingService.createPnr(any(), any())).thenThrow(new PaymentUnavailableException("no answer", null));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.title").value("Payment unavailable"));
    }

    @Test
    void should_answer_409_when_the_price_changed() throws Exception {
        when(bookingService.createPnr(any(), any())).thenThrow(new PriceChangedException("The price is now 200.00 USD"));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Price changed"));
    }

    @Test
    void should_require_a_well_formed_payment_token() throws Exception {
        mvc.perform(createRequest(BODY.replace("\"paymentToken\": \"tok_visa\",", ""), "key-12345678"))
                .andExpect(status().isBadRequest());
        mvc.perform(createRequest(BODY.replace("tok_visa", "4242 4242 4242 4242"), "key-12345678"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bookingService);
    }

    @Test
    void should_return_200_for_idempotent_replay() throws Exception {
        when(bookingService.createPnr(any(), any())).thenReturn(new BookingResult(PNR, false));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locator").value("ABC234"));
    }

    @Test
    void should_require_idempotency_key() throws Exception {
        mvc.perform(post("/api/v1/pnrs").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(bookingService);
    }

    @Test
    void should_reject_invalid_email_before_reaching_the_service() throws Exception {
        mvc.perform(createRequest(BODY.replace("priya@example.com", "not-an-email"), "key-12345678"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bookingService);
    }

    @Test
    void should_reject_malformed_seat_number() throws Exception {
        mvc.perform(createRequest(BODY.replace("\"11D\"", "\"11Z\""), "key-12345678"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bookingService);
    }

    @Test
    void should_return_409_when_seat_is_taken() throws Exception {
        when(bookingService.createPnr(any(), any()))
                .thenThrow(new SeatUnavailableException("Seat 11D on SK501 2026-11-02 is no longer available"));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"));
    }

    @Test
    void should_return_422_when_idempotency_key_is_reused_for_another_booking() throws Exception {
        when(bookingService.createPnr(any(), any()))
                .thenThrow(new IdempotencyKeyReusedException("This Idempotency-Key was already used for a different booking"));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void should_never_leak_internal_errors() throws Exception {
        when(bookingService.createPnr(any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(createRequest(BODY, "key-12345678"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Something went wrong. Please try again."));
    }

    private static org.springframework.test.web.servlet.RequestBuilder createRequest(String body, String key) {
        return post("/api/v1/pnrs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
