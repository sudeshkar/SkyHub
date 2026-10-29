package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.HeldSeat;
import com.skyhub.booking.application.HoldService;
import com.skyhub.booking.application.HoldView;
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

@WebMvcTest(HoldsController.class)
@Import(WebSliceSecurity.class)
class HoldsControllerTest {

    private static final String HOLD_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String BODY = """
            {"items": [{"flightNumber": "SK501", "departureDate": "2026-11-02", "seatNumber": "11D"}]}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private HoldService holdService;

    @Test
    void should_create_a_hold_and_return_its_id_and_expiry() throws Exception {
        when(holdService.createHold(any())).thenReturn(new HoldView(HOLD_ID, Instant.parse("2026-10-29T10:10:00Z"),
                List.of(new HeldSeat("SK501", LocalDate.of(2026, 11, 2), "11D"))));

        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value(HOLD_ID))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-29T10:10:00Z"))
                .andExpect(jsonPath("$.items[0].seatNumber").value("11D"));
    }

    @Test
    void should_answer_409_when_a_seat_is_not_available() throws Exception {
        when(holdService.createHold(any())).thenThrow(new SeatUnavailableException("Seat 11D is no longer available"));

        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void should_reject_a_hold_with_no_seats_or_a_bad_seat_number() throws Exception {
        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content("{\"items\": []}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content("""
                        {"items": [{"flightNumber": "SK501", "departureDate": "2026-11-02", "seatNumber": "<script>"}]}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(holdService);
    }

    @Test
    void should_release_a_hold_and_answer_204() throws Exception {
        mvc.perform(delete("/api/v1/holds/" + HOLD_ID)).andExpect(status().isNoContent());

        verify(holdService).releaseHold(HOLD_ID);
    }

    @Test
    void should_reject_a_hold_id_that_is_not_a_uuid() throws Exception {
        mvc.perform(delete("/api/v1/holds/not-a-uuid")).andExpect(status().isBadRequest());

        verifyNoInteractions(holdService);
    }
}
