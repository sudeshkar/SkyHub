package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AffectedBooking;
import com.skyhub.booking.application.BookingDisruption;
import com.skyhub.booking.application.DisruptionService;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.StaffRole;
import com.skyhub.booking.domain.DisruptionType;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrSegment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

@WebMvcTest(DisruptionsController.class)
@Import(WebSliceSecurity.class)
class DisruptionsControllerTest {

    private static final String DELAY = """
            {"flightNumber": "SK501", "departureDate": "2026-11-02", "type": "DELAYED",
             "revisedDeparture": "2026-11-02T14:30:00Z", "reason": "Late inbound aircraft"}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @MockitoBean
    private DisruptionService disruptionService;

    private String ops;

    @BeforeEach
    void logIn() {
        ops = "Bearer " + staffTokens.issue("priya.ops", StaffRole.OPS).value();
    }

    private static MockHttpServletRequestBuilder record(String body, String key) {
        MockHttpServletRequestBuilder request = post("/api/v1/ops/disruptions").contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Authorization", key);
    }

    @Test
    void should_record_a_disruption_for_operations_and_report_the_affected_bookings() throws Exception {
        when(disruptionService.record(argThat(c -> c.type() == DisruptionType.DELAYED
                && c.revisedDeparture().equals(Instant.parse("2026-11-02T14:30:00Z"))
                && c.flightNumber().equals("SK501")
                && c.recordedBy().equals("priya.ops")))).thenReturn(42);

        mvc.perform(record(DELAY, ops))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.affectedBookings").value(42));
    }

    @Test
    void should_refuse_anyone_who_is_not_logged_in_as_staff() throws Exception {
        mvc.perform(record(DELAY, null)).andExpect(status().isUnauthorized());
        mvc.perform(record(DELAY, "Bearer guess")).andExpect(status().isUnauthorized());
        // a traveller's booking token is a valid token, but not a staff one
        mvc.perform(record(DELAY, "Bearer " + manageTokens.issue("ABC234").value())).andExpect(status().isForbidden());
        verifyNoInteractions(disruptionService);
    }

    @Test
    void should_answer_400_when_the_request_makes_no_sense() throws Exception {
        when(disruptionService.record(argThat(c -> c.type() == DisruptionType.DELAYED && c.revisedDeparture() == null)))
                .thenThrow(new InvalidBookingException("A delay needs a revised departure time"));

        mvc.perform(record("""
                        {"flightNumber": "SK501", "departureDate": "2026-11-02", "type": "DELAYED"}""", ops))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A delay needs a revised departure time"));
        mvc.perform(record("""
                        {"flightNumber": "../x", "departureDate": "2026-11-02", "type": "CANCELLED"}""", ops))
                .andExpect(status().isBadRequest());
        mvc.perform(record("""
                        {"flightNumber": "SK501", "departureDate": "2026-11-02", "type": "EXPLODED"}""", ops))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_list_the_affected_bookings_for_staff_without_any_personal_data() throws Exception {
        when(disruptionService.affectedBookings("SK501", LocalDate.of(2026, 11, 2))).thenReturn(List.of(
                new AffectedBooking("ABC234", 2, 1, List.of(new PnrSegment("SK001", LocalDate.of(2026, 11, 2)),
                        new PnrSegment("SK501", LocalDate.of(2026, 11, 2))))));

        mvc.perform(get("/api/v1/ops/flights/SK501/departures/2026-11-02/bookings").header("Authorization", ops))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.flightNumber").value("SK501"))
                .andExpect(jsonPath("$.items[0].locator").value("ABC234"))
                .andExpect(jsonPath("$.items[0].passengerCount").value(2))
                .andExpect(jsonPath("$.items[0].segmentIndex").value(1))
                .andExpect(jsonPath("$.items[0].segments[1].flightNumber").value("SK501"))
                .andExpect(jsonPath("$.items[0].contactEmail").doesNotExist())
                .andExpect(jsonPath("$.items[0].passengers").doesNotExist());
    }

    @Test
    void should_keep_the_affected_bookings_from_travellers_and_anonymous_callers() throws Exception {
        mvc.perform(get("/api/v1/ops/flights/SK501/departures/2026-11-02/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ops/flights/SK501/departures/2026-11-02/bookings")
                        .header("Authorization", "Bearer " + manageTokens.issue("ABC234").value()))
                .andExpect(status().isForbidden());
        verify(disruptionService, never()).affectedBookings(any(), any());
    }

    @Test
    void should_refuse_a_flight_number_that_is_not_one() throws Exception {
        mvc.perform(get("/api/v1/ops/flights/..%2Fx/departures/2026-11-02/bookings").header("Authorization", ops))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/ops/flights/SK501/departures/not-a-date/bookings").header("Authorization", ops))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_show_a_traveller_the_disruptions_on_their_own_booking() throws Exception {
        when(disruptionService.forBooking("ABC234")).thenReturn(List.of(new BookingDisruption(1, "SK901",
                LocalDate.of(2026, 11, 2), DisruptionType.DELAYED, Instant.parse("2026-11-02T14:30:00Z"), "Weather")));

        mvc.perform(get("/api/v1/pnrs/ABC234/disruptions").with(SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.subject("ABC234")).authorities(new SimpleGrantedAuthority("SCOPE_manage"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].segmentIndex").value(1))
                .andExpect(jsonPath("$.items[0].type").value("DELAYED"))
                .andExpect(jsonPath("$.items[0].revisedDeparture").value("2026-11-02T14:30:00Z"))
                .andExpect(jsonPath("$.items[0].reason").value("Weather"));
    }

    @Test
    void should_not_show_one_booking_to_the_holder_of_another() throws Exception {
        mvc.perform(get("/api/v1/pnrs/ABC234/disruptions").with(SecurityMockMvcRequestPostProcessors.jwt()
                        .jwt(token -> token.subject("OTHER1")).authorities(new SimpleGrantedAuthority("SCOPE_manage"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pnrs/ABC234/disruptions")).andExpect(status().isUnauthorized());
        verify(disruptionService, org.mockito.Mockito.never()).forBooking("ABC234");
    }
}
