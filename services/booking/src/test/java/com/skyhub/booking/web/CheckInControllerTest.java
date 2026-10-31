package com.skyhub.booking.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BoardingPass;
import com.skyhub.booking.application.CheckInResult;
import com.skyhub.booking.application.BoardingPassVerifier;
import com.skyhub.booking.application.CheckInService;
import com.skyhub.booking.domain.CheckInClosedException;
import com.skyhub.booking.domain.NotCheckedInException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CheckInController.class)
@Import(WebSliceSecurity.class)
class CheckInControllerTest {

    private static final CheckInResult RESULT = new CheckInResult(List.of(new BoardingPass("PERERA/PRIYA", "ABC234", "SK501",
            LocalDate.of(2026, 11, 2), "11D", 7, "M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.sig")), true);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private BoardingPassVerifier boardingPassVerifier;

    /** A request made with a manage token issued for booking ABC234, as the security rules require. */
    private static RequestPostProcessor owner() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.subject("ABC234"))
                .authorities(new SimpleGrantedAuthority("SCOPE_manage"));
    }

    private static MockHttpServletRequestBuilder checkIn(String body) {
        return post("/api/v1/pnrs/ABC234/check-ins").with(owner()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void should_answer_201_with_the_boarding_passes_when_checked_in_now() throws Exception {
        when(checkInService.checkIn("ABC234", 0)).thenReturn(RESULT);

        mvc.perform(checkIn("{\"segmentIndex\": 0}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.passes[0].passengerName").value("PERERA/PRIYA"))
                .andExpect(jsonPath("$.passes[0].sequence").value(7))
                .andExpect(jsonPath("$.passes[0].barcode").value("M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.sig"));
    }

    @Test
    void should_answer_200_when_the_flight_was_already_checked_in() throws Exception {
        when(checkInService.checkIn("ABC234", 0)).thenReturn(new CheckInResult(RESULT.passes(), false));

        mvc.perform(checkIn("{\"segmentIndex\": 0}")).andExpect(status().isOk());
    }

    @Test
    void should_answer_409_when_check_in_is_not_open() throws Exception {
        when(checkInService.checkIn("ABC234", 0)).thenThrow(new CheckInClosedException("Check-in opens at 2026-11-01T10:00:00Z"));

        mvc.perform(checkIn("{\"segmentIndex\": 0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Check-in unavailable"))
                .andExpect(jsonPath("$.detail").value("Check-in opens at 2026-11-01T10:00:00Z"));
    }

    @Test
    void should_reject_a_flight_index_outside_the_contract() throws Exception {
        mvc.perform(checkIn("{\"segmentIndex\": 9}")).andExpect(status().isBadRequest());
        mvc.perform(checkIn("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void should_return_boarding_passes_for_a_checked_in_flight() throws Exception {
        when(checkInService.boardingPasses("ABC234", 0)).thenReturn(new CheckInResult(RESULT.passes(), false));

        mvc.perform(get("/api/v1/pnrs/ABC234/check-ins/0").with(owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passes[0].seatNumber").value("11D"));
    }

    @Test
    void should_answer_404_when_the_flight_is_not_checked_in() throws Exception {
        when(checkInService.boardingPasses("ABC234", 1)).thenThrow(new NotCheckedInException("This flight is not checked in yet"));

        mvc.perform(get("/api/v1/pnrs/ABC234/check-ins/1").with(owner()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not checked in"));
    }
}
