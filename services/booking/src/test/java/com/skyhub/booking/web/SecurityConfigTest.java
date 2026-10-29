package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BookingService;
import com.skyhub.booking.application.ManageBookingService;
import com.skyhub.booking.application.ManageSession;
import com.skyhub.booking.application.ManageToken;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.SeatChangeCommand;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.security.JwtManageTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Who may reach what: the rules in SecurityConfig, run through the real filter chain with real tokens. */
@WebMvcTest({PnrsController.class, ManageBookingController.class})
@Import(WebSliceSecurity.class)
class SecurityConfigTest {

    private static final PnrView PNR = new PnrView("ABC234", PnrStatus.CONFIRMED, "p****@example.com",
            List.of(new PnrView.SegmentView("SK501", LocalDate.of(2026, 11, 2))),
            List.of(new PnrView.PassengerView("Priya", "Perera", List.of("11D"))),
            Instant.parse("2026-10-29T10:00:00Z"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ManageTokenIssuer tokens;

    @Autowired
    private JwtEncoder encoder;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private com.skyhub.booking.application.PricingService pricing;

    @MockitoBean
    private ManageBookingService manageBooking;

    private String bearer(String locator) {
        return "Bearer " + tokens.issue(locator).value();
    }

    @Test
    void should_deny_endpoints_outside_the_contract() throws Exception {
        // Without credentials: 401. With a valid token: still refused, 403. Nothing outside the contract is reachable.
        mvc.perform(get("/api/v1/admin/pnrs")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/pnrs/ABC234")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/pnrs").header("Authorization", bearer("ABC234"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/pnrs/ABC234").header("Authorization", bearer("ABC234"))).andExpect(status().isForbidden());
    }

    @Test
    void should_send_security_headers_and_no_session_cookie() throws Exception {
        when(manageBooking.find("ABC234")).thenReturn(PNR);

        mvc.perform(get("/api/v1/pnrs/ABC234").header("Authorization", bearer("ABC234")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void should_require_a_token_to_read_a_booking() throws Exception {
        mvc.perform(get("/api/v1/pnrs/ABC234")).andExpect(status().isUnauthorized());

        verifyNoInteractions(manageBooking);
    }

    @Test
    void should_let_a_token_read_only_its_own_booking() throws Exception {
        when(manageBooking.find("ABC234")).thenReturn(PNR);

        mvc.perform(get("/api/v1/pnrs/ABC234").header("Authorization", bearer("ABC234")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.locator").value("ABC234"));
        mvc.perform(get("/api/v1/pnrs/XYZ789").header("Authorization", bearer("ABC234")))
                .andExpect(status().isForbidden());
    }

    @Test
    void should_reject_garbage_and_expired_tokens_with_401() throws Exception {
        var past = new JwtManageTokenIssuer(encoder, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(15));

        mvc.perform(get("/api/v1/pnrs/ABC234").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pnrs/ABC234").header("Authorization", "Bearer " + past.issue("ABC234").value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_protect_cancel_and_seat_change_the_same_way() throws Exception {
        String change = "{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"30C\"}";
        when(manageBooking.cancel("ABC234")).thenReturn(PNR);
        when(manageBooking.changeSeat(eq("ABC234"), any(SeatChangeCommand.class))).thenReturn(PNR);

        mvc.perform(post("/api/v1/pnrs/ABC234/cancellation")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/pnrs/ABC234/cancellation").header("Authorization", bearer("XYZ789")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pnrs/ABC234/cancellation").header("Authorization", bearer("ABC234")))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/pnrs/ABC234/seat-assignments").contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/pnrs/ABC234/seat-assignments").header("Authorization", bearer("XYZ789"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/pnrs/ABC234/seat-assignments").header("Authorization", bearer("ABC234"))
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk());
    }

    @Test
    void should_open_a_session_without_a_token_and_never_cache_it() throws Exception {
        when(manageBooking.openSession("ABC234", "Perera"))
                .thenReturn(new ManageSession(new ManageToken("signed.jwt.value", Instant.parse("2026-10-29T10:15:00Z")), PNR));

        mvc.perform(post("/api/v1/pnrs/ABC234/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("signed.jwt.value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-29T10:15:00Z"))
                .andExpect(jsonPath("$.pnr.locator").value("ABC234"));
    }

    @Test
    void should_answer_a_wrong_last_name_with_404_and_validate_input() throws Exception {
        when(manageBooking.openSession("ABC234", "Silva"))
                .thenThrow(new PnrNotFoundException("No booking matches this reference and last name"));

        mvc.perform(post("/api/v1/pnrs/ABC234/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Silva\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No booking matches this reference and last name"));
        mvc.perform(post("/api/v1/pnrs/abc/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"lastName\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pnrs/ABC234/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_map_manage_conflicts_to_409() throws Exception {
        String change = "{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"30C\"}";
        when(manageBooking.changeSeat(eq("ABC234"), any(SeatChangeCommand.class)))
                .thenThrow(new SeatUnavailableException("Seat 30C on SK501 2026-11-02 is no longer available"))
                .thenThrow(new BookingCancelledException("Booking ABC234 is cancelled"));

        for (String title : List.of("Seat unavailable", "Booking cancelled")) {
            mvc.perform(put("/api/v1/pnrs/ABC234/seat-assignments").header("Authorization", bearer("ABC234"))
                            .contentType(MediaType.APPLICATION_JSON).content(change))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value(title));
        }
    }
}
