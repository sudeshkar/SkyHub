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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PnrsController.class)
class PnrsControllerTest {

    private static final String BODY = """
            {
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
    void should_return_404_for_unknown_booking() throws Exception {
        when(bookingService.findPnr("ZZZZZZ", "Perera"))
                .thenThrow(new PnrNotFoundException("No booking matches this reference and last name"));

        mvc.perform(get("/api/v1/pnrs/ZZZZZZ").param("lastName", "Perera"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No booking matches this reference and last name"));
    }

    @Test
    void should_never_leak_internal_errors() throws Exception {
        when(bookingService.findPnr(any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(get("/api/v1/pnrs/ABC234").param("lastName", "Perera"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Something went wrong. Please try again."));
    }

    private static org.springframework.test.web.servlet.RequestBuilder createRequest(String body, String key) {
        return post("/api/v1/pnrs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
