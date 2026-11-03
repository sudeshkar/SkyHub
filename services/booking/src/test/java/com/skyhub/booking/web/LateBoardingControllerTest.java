package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BoardingPass;
import com.skyhub.booking.application.BoardingPassVerifier;
import com.skyhub.booking.application.BoardingReversalService;
import com.skyhub.booking.application.BoardingService;
import com.skyhub.booking.application.CheckInService;
import com.skyhub.booking.application.FlightBoardingReport;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.StaffRole;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Who may board a passenger after the gate has closed, and the exact answers (ADR-0076), with the real security rules. */
@WebMvcTest(CheckInController.class)
@Import(WebSliceSecurity.class)
class LateBoardingControllerTest {

    private static final String URL = "/api/v1/ops/boarding-passes/late-boardings";
    private static final String BODY = "{\"barcode\":\"M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.sig\",\"reason\":\"Held at security\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @MockitoBean
    private BoardingService boardingService;

    @MockitoBean
    private FlightBoardingReport report;

    @MockitoBean
    private BoardingReversalService reversals;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private BoardingPassVerifier verifier;

    private ResultActions lateBoard(String body, String bearer) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(bearer == null ? request : request.header("Authorization", bearer));
    }

    private String as(String username, StaffRole role) {
        return "Bearer " + staffTokens.issue(username, role).value();
    }

    @Test
    void should_board_late_for_operations_taking_the_person_from_the_token_and_never_cache_the_answer() throws Exception {
        BoardingPass pass = new BoardingPass("PERERA/PRIYA", 0, "ABC234", "SK501", LocalDate.of(2026, 11, 2), "11D", 7, "M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.sig");
        when(boardingService.boardLate(anyString(), anyString(), anyString()))
                .thenReturn(new BoardingPassVerifier.Verdict(true, null, pass, 0));

        lateBoard(BODY, as("priya.ops", StaffRole.OPS)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.pass.passengerName").value("PERERA/PRIYA"));

        verify(boardingService).boardLate("M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.sig", "Held at security", "priya.ops");
    }

    @Test
    void should_answer_a_refusal_as_a_200_with_the_reason_and_nothing_about_the_passenger() throws Exception {
        when(boardingService.boardLate(anyString(), anyString(), anyString()))
                .thenReturn(new BoardingPassVerifier.Verdict(false, BoardingPassVerifier.Reason.FLIGHT_DEPARTED, null, -1));

        lateBoard(BODY, as("root.admin", StaffRole.ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("FLIGHT_DEPARTED")).andExpect(jsonPath("$.pass").doesNotExist());
    }

    @Test
    void should_refuse_a_gate_agent_a_traveller_and_nobody_and_touch_nothing() throws Exception {
        lateBoard(BODY, as("gate.one", StaffRole.GATE)).andExpect(status().isForbidden());
        lateBoard(BODY, "Bearer " + manageTokens.issue("ABC234", 0).value()).andExpect(status().isForbidden());
        lateBoard(BODY, null).andExpect(status().isUnauthorized());

        verifyNoInteractions(boardingService);
    }

    @Test
    void should_answer_400_for_a_missing_blank_or_too_long_reason_or_a_missing_pass_without_calling_the_service() throws Exception {
        String ops = as("priya.ops", StaffRole.OPS);
        lateBoard("{\"barcode\":\"x\"}", ops).andExpect(status().isBadRequest());
        lateBoard("{\"barcode\":\"x\",\"reason\":\"\"}", ops).andExpect(status().isBadRequest());
        lateBoard("{\"barcode\":\"x\",\"reason\":\"%s\"}".formatted("y".repeat(201)), ops).andExpect(status().isBadRequest());
        lateBoard("{\"reason\":\"Held\"}", ops).andExpect(status().isBadRequest());

        verifyNoInteractions(boardingService);
    }

    @Test
    void should_answer_400_when_the_service_says_the_reason_is_not_acceptable() throws Exception {
        when(boardingService.boardLate(anyString(), anyString(), anyString())).thenThrow(new InvalidBookingException("A reason is needed"));

        lateBoard("{\"barcode\":\"x\",\"reason\":\"   \"}", as("priya.ops", StaffRole.OPS)).andExpect(status().isBadRequest());
    }
}
