package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.application.rebooking.BulkRebookingService;
import com.skyhub.booking.application.rebooking.MoveOutcome;
import com.skyhub.booking.application.rebooking.MoveResult;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.StaffRole;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(BulkRebookingController.class)
@Import(WebSliceSecurity.class)
class BulkRebookingControllerTest {

    private static final String URL = "/api/v1/ops/rebookings";
    private static final String BODY = """
            {"moves":[
              {"locator":"AAAAAA","segmentIndex":0,"flightNumber":"SK502","departureDate":"2026-11-02"},
              {"locator":"BBBBBB","segmentIndex":1,"flightNumber":"SK502","departureDate":"2026-11-02"}]}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @Autowired
    private AccountTokenIssuer accountTokens;

    @MockitoBean
    private BulkRebookingService bulk;

    private String ops;

    @BeforeEach
    void logIn() {
        ops = "Bearer " + staffTokens.issue("sam.ops", StaffRole.OPS).value();
    }

    private static MockHttpServletRequestBuilder request(String body, String bearer) {
        MockHttpServletRequestBuilder request = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    @Test
    void should_move_as_the_logged_in_staff_member_and_answer_each_move_without_personal_data() throws Exception {
        when(bulk.rebookAll(argThat(moves -> moves != null && moves.size() == 2
                && moves.stream().allMatch(m -> m.rebookedBy().equals("sam.ops"))
                && moves.get(0).locator().equals("AAAAAA") && moves.get(0).departureDate().equals(LocalDate.of(2026, 11, 2))
                && moves.get(1).segmentIndex() == 1)))
                .thenReturn(List.of(new MoveResult("AAAAAA", 0, MoveOutcome.MOVED, null, List.of("14A", "14B")),
                        new MoveResult("BBBBBB", 1, MoveOutcome.REFUSED, "SK502 is cancelled", null)));

        mvc.perform(request(BODY, ops))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].locator").value("AAAAAA"))
                .andExpect(jsonPath("$.results[0].outcome").value("MOVED"))
                .andExpect(jsonPath("$.results[0].seats[1]").value("14B"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].detail").value("SK502 is cancelled"))
                .andExpect(jsonPath("$.results[0].passengers").doesNotExist());
    }

    @Test
    void should_keep_it_from_anonymous_callers_travellers_and_booking_holders() throws Exception {
        mvc.perform(request(BODY, null)).andExpect(status().isUnauthorized());
        mvc.perform(request(BODY, "Bearer " + manageTokens.issue("AAAAAA").value())).andExpect(status().isForbidden());
        mvc.perform(request(BODY, "Bearer " + accountTokens.issue(7L).value())).andExpect(status().isForbidden());

        verifyNoInteractions(bulk);
    }

    @Test
    void should_answer_400_for_a_body_that_breaks_the_contract_before_the_service_is_asked() throws Exception {
        String tooMany = IntStream.rangeClosed(1, 51).mapToObj(i ->
                "{\"locator\":\"L%05d\",\"segmentIndex\":0,\"flightNumber\":\"SK502\",\"departureDate\":\"2026-11-02\"}".formatted(i))
                .collect(Collectors.joining(",", "{\"moves\":[", "]}"));

        mvc.perform(request("{\"moves\":[]}", ops)).andExpect(status().isBadRequest());
        mvc.perform(request(tooMany, ops)).andExpect(status().isBadRequest());
        mvc.perform(request("{\"moves\":[{\"locator\":\"abc\",\"segmentIndex\":0,\"flightNumber\":\"SK502\",\"departureDate\":\"2026-11-02\"}]}", ops))
                .andExpect(status().isBadRequest());
        mvc.perform(request("{\"moves\":[{\"locator\":\"AAAAAA\",\"segmentIndex\":9,\"flightNumber\":\"SK502\",\"departureDate\":\"2026-11-02\"}]}", ops))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bulk);
    }

    @Test
    void should_answer_400_with_the_reason_when_the_service_finds_the_set_ambiguous() throws Exception {
        when(bulk.rebookAll(anyList())).thenThrow(new InvalidBookingException("AAAAAA flight 0 is listed twice"));

        mvc.perform(request(BODY, ops)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("AAAAAA flight 0 is listed twice"));
    }
}
