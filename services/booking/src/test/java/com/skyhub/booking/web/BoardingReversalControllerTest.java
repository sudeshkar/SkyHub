package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.BoardingPassVerifier;
import com.skyhub.booking.application.BoardingReversalService;
import com.skyhub.booking.application.BoardingService;
import com.skyhub.booking.application.CheckInService;
import com.skyhub.booking.application.FlightBoardingReport;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.NotBoardedException;
import com.skyhub.booking.domain.StaffRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Who may take a boarding back, and the exact status codes (ADR-0064), with the real security rules. */
@WebMvcTest(CheckInController.class)
@Import(WebSliceSecurity.class)
class BoardingReversalControllerTest {

    private static final String URL = "/api/v1/ops/boardings/reversals";
    private static final String BODY = "{\"locator\":\"ABC234\",\"segmentIndex\":0,\"passengerIndex\":1,\"reason\":\"Scanned the wrong passenger\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @MockitoBean
    private BoardingReversalService reversals;

    @MockitoBean
    private FlightBoardingReport flightBoardingReport;

    @MockitoBean
    private CheckInService checkInService;

    @MockitoBean
    private BoardingPassVerifier verifier;

    @MockitoBean
    private BoardingService boardingService;

    private ResultActions undo(String body, String bearer) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(bearer == null ? request : request.header("Authorization", bearer));
    }

    private String as(String username, StaffRole role) {
        return "Bearer " + staffTokens.issue(username, role).value();
    }

    @Test
    void should_answer_204_and_record_the_person_from_the_token_not_from_the_request() throws Exception {
        undo(BODY, as("priya.ops", StaffRole.OPS)).andExpect(status().isNoContent());

        verify(reversals).undo("ABC234", 0, 1, "Scanned the wrong passenger", "priya.ops");
    }

    @Test
    void should_let_an_administrator_do_it_too() throws Exception {
        undo(BODY, as("root.admin", StaffRole.ADMIN)).andExpect(status().isNoContent());
    }

    @Test
    void should_refuse_a_gate_agent_a_traveller_and_nobody_and_touch_nothing() throws Exception {
        undo(BODY, as("gate.one", StaffRole.GATE)).andExpect(status().isForbidden());
        undo(BODY, "Bearer " + manageTokens.issue("ABC234", 0).value()).andExpect(status().isForbidden());
        undo(BODY, null).andExpect(status().isUnauthorized());

        verifyNoInteractions(reversals);
    }

    @Test
    void should_answer_404_not_boarded_when_there_is_nothing_to_undo() throws Exception {
        doThrow(new NotBoardedException("That passenger has not boarded this flight, so there is nothing to undo"))
                .when(reversals).undo(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString());

        undo(BODY, as("priya.ops", StaffRole.OPS)).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Not boarded"));
    }

    @Test
    void should_answer_400_for_a_missing_blank_or_too_long_reason_or_a_negative_index_without_calling_the_service() throws Exception {
        String ops = as("priya.ops", StaffRole.OPS);
        undo("{\"locator\":\"ABC234\",\"segmentIndex\":0,\"passengerIndex\":1}", ops).andExpect(status().isBadRequest());
        undo("{\"locator\":\"ABC234\",\"segmentIndex\":0,\"passengerIndex\":1,\"reason\":\"\"}", ops).andExpect(status().isBadRequest());
        undo("{\"locator\":\"ABC234\",\"segmentIndex\":0,\"passengerIndex\":1,\"reason\":\"%s\"}".formatted("x".repeat(201)), ops)
                .andExpect(status().isBadRequest());
        undo("{\"locator\":\"ABC234\",\"segmentIndex\":-1,\"passengerIndex\":1,\"reason\":\"x\"}", ops).andExpect(status().isBadRequest());

        verifyNoInteractions(reversals);
    }
}
