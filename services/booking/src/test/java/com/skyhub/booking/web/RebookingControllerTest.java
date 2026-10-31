package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.application.rebooking.RebookCommand;
import com.skyhub.booking.application.rebooking.RebookingRefusedException;
import com.skyhub.booking.application.rebooking.RebookingResult;
import com.skyhub.booking.application.rebooking.RebookingService;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.StaffRole;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(RebookingController.class)
@Import(WebSliceSecurity.class)
class RebookingControllerTest {

    private static final String URL = "/api/v1/ops/pnrs/ABC234/rebooking";
    private static final String BODY = "{\"segmentIndex\":0,\"flightNumber\":\"SK501\",\"departureDate\":\"2026-11-03\"}";
    private static final PnrSegment FROM = new PnrSegment("SK501", LocalDate.of(2026, 11, 2));
    private static final PnrSegment TO = new PnrSegment("SK501", LocalDate.of(2026, 11, 3));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @MockitoBean
    private RebookingService rebooking;

    private String ops;

    @BeforeEach
    void logIn() {
        ops = "Bearer " + staffTokens.issue("priya.ops", StaffRole.OPS).value();
    }

    private static MockHttpServletRequestBuilder rebook(String body, String bearer) {
        MockHttpServletRequestBuilder request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    @Test
    void should_move_the_booking_as_the_logged_in_staff_member_and_answer_without_personal_data() throws Exception {
        when(rebooking.rebook(argThat(c -> c.locator().equals("ABC234") && c.segmentIndex() == 0
                && c.flightNumber().equals("SK501") && c.departureDate().equals(LocalDate.of(2026, 11, 3))
                && c.rebookedBy().equals("priya.ops"))))
                .thenReturn(new RebookingResult("ABC234", 0, FROM, TO, List.of("14A", "14B"), true));

        mvc.perform(rebook(BODY, ops))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.locator").value("ABC234"))
                .andExpect(jsonPath("$.from.departureDate").value("2026-11-02"))
                .andExpect(jsonPath("$.to.departureDate").value("2026-11-03"))
                .andExpect(jsonPath("$.seats[0]").value("14A"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.passengers").doesNotExist());
    }

    @Test
    void should_keep_it_from_travellers_and_anonymous_callers() throws Exception {
        mvc.perform(rebook(BODY, null)).andExpect(status().isUnauthorized());
        mvc.perform(rebook(BODY, "Bearer " + manageTokens.issue("ABC234", 0).value())).andExpect(status().isForbidden());

        verifyNoInteractions(rebooking);
    }

    @Test
    void should_answer_409_with_the_reason_when_a_rule_refuses() throws Exception {
        when(rebooking.rebook(any(RebookCommand.class)))
                .thenThrow(new RebookingRefusedException("SK501 on 2026-11-02 has no recorded delay or cancellation, so its bookings are not moved"));

        mvc.perform(rebook(BODY, ops))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Rebooking refused"))
                .andExpect(jsonPath("$.detail").value("SK501 on 2026-11-02 has no recorded delay or cancellation, so its bookings are not moved"));
    }

    @Test
    void should_answer_404_for_an_unknown_booking() throws Exception {
        when(rebooking.rebook(any(RebookCommand.class))).thenThrow(new PnrNotFoundException("No booking matches this reference"));

        mvc.perform(rebook(BODY, ops)).andExpect(status().isNotFound());
    }

    @Test
    void should_refuse_a_request_that_makes_no_sense_without_calling_the_service() throws Exception {
        mvc.perform(rebook("{\"flightNumber\":\"SK501\",\"departureDate\":\"2026-11-03\"}", ops)).andExpect(status().isBadRequest());
        mvc.perform(rebook("{\"segmentIndex\":9,\"flightNumber\":\"SK501\",\"departureDate\":\"2026-11-03\"}", ops)).andExpect(status().isBadRequest());
        mvc.perform(rebook("{\"segmentIndex\":0,\"flightNumber\":\"../x\",\"departureDate\":\"2026-11-03\"}", ops)).andExpect(status().isBadRequest());
        mvc.perform(rebook("{\"segmentIndex\":0,\"flightNumber\":\"SK501\",\"departureDate\":\"soon\"}", ops)).andExpect(status().isBadRequest());

        verify(rebooking, never()).rebook(any());
    }
}
