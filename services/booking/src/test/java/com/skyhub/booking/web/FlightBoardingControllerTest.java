package com.skyhub.booking.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BoardingPassVerifier;
import com.skyhub.booking.application.BoardingReversalService;
import com.skyhub.booking.application.BoardingService;
import com.skyhub.booking.application.CheckInService;
import com.skyhub.booking.application.FlightBoarding;
import com.skyhub.booking.application.FlightBoardingReport;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.StaffRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Who may see who has boarded a flight, and what the answer holds (ADR-0065), with the real security rules. */
@WebMvcTest(CheckInController.class)
@Import(WebSliceSecurity.class)
class FlightBoardingControllerTest {

    private static final String URL = "/api/v1/ops/flights/SK501/departures/2026-11-03/boardings";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @MockitoBean
    private FlightBoardingReport report;

    @MockitoBean
    private BoardingReversalService reversals;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private BoardingPassVerifier verifier;

    @MockitoBean
    private BoardingService boardingService;

    private ResultActions fetch(String bearer) throws Exception {
        var request = get(URL);
        return mvc.perform(bearer == null ? request : request.header("Authorization", bearer));
    }

    private String as(String username, StaffRole role) {
        return "Bearer " + staffTokens.issue(username, role).value();
    }

    @Test
    void should_answer_with_the_counts_and_who_boarded_without_any_names_and_never_cache_it() throws Exception {
        LocalDate date = LocalDate.of(2026, 11, 3);
        when(report.of("SK501", date)).thenReturn(new FlightBoarding("SK501", date, 3,
                List.of(new FlightBoarding.Boarding("ABC234", 0, 1, "gate.one", Instant.parse("2026-11-03T09:00:00Z")))));

        fetch(as("priya.ops", StaffRole.OPS)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.flightNumber").value("SK501"))
                .andExpect(jsonPath("$.departureDate").value("2026-11-03"))
                .andExpect(jsonPath("$.checkedIn").value(3))
                .andExpect(jsonPath("$.boarded").value(1))
                .andExpect(jsonPath("$.waiting").value(2))
                .andExpect(jsonPath("$.boardings[0].locator").value("ABC234"))
                .andExpect(jsonPath("$.boardings[0].segmentIndex").value(0))
                .andExpect(jsonPath("$.boardings[0].passengerIndex").value(1))
                .andExpect(jsonPath("$.boardings[0].boardedBy").value("gate.one"))
                .andExpect(jsonPath("$.boardings[0].boardedAt").value("2026-11-03T09:00:00Z"))
                .andExpect(jsonPath("$.boardings[0].passengerName").doesNotExist());
    }

    @Test
    void should_let_an_administrator_see_it_too() throws Exception {
        when(report.of("SK501", LocalDate.of(2026, 11, 3))).thenReturn(new FlightBoarding("SK501", LocalDate.of(2026, 11, 3), 0, List.of()));

        fetch(as("root.admin", StaffRole.ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.boardings.length()").value(0));
    }

    @Test
    void should_refuse_a_gate_agent_a_traveller_and_nobody_and_ask_for_nothing() throws Exception {
        fetch(as("gate.one", StaffRole.GATE)).andExpect(status().isForbidden());   // a gate agent does not see who is booked on a flight (ADR-0060)
        fetch("Bearer " + manageTokens.issue("ABC234", 0).value()).andExpect(status().isForbidden());
        fetch(null).andExpect(status().isUnauthorized());

        verifyNoInteractions(report);
    }

    @Test
    void should_refuse_a_flight_number_that_is_not_one_without_asking() throws Exception {
        mvc.perform(get("/api/v1/ops/flights/NOPE!/departures/2026-11-03/boardings").header("Authorization", as("priya.ops", StaffRole.OPS)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(report);
    }

    @Test
    void should_ask_for_the_flight_and_date_in_the_address() throws Exception {
        when(report.of("SK501", LocalDate.of(2026, 11, 3))).thenReturn(new FlightBoarding("SK501", LocalDate.of(2026, 11, 3), 0, List.of()));

        fetch(as("priya.ops", StaffRole.OPS)).andExpect(status().isOk());

        verify(report).of("SK501", LocalDate.of(2026, 11, 3));
    }
}
