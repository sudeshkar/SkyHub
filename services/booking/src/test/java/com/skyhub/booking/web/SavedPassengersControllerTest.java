package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.SavedPassengerService;
import com.skyhub.booking.application.SavedPassengers;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.SavedPassengerLimitException;
import com.skyhub.booking.domain.SavedPassengerNotFoundException;
import com.skyhub.booking.domain.StaffRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Who may keep and read saved passengers, and the exact answers (ADR-0078), with the real security rules. */
@WebMvcTest(SavedPassengersController.class)
@Import(WebSliceSecurity.class)
class SavedPassengersControllerTest {

    private static final String URL = "/api/v1/accounts/me/passengers";
    private static final String BODY = "{\"firstName\":\"Priya\",\"lastName\":\"Perera\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountTokenIssuer accountTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @MockitoBean
    private SavedPassengerService passengers;

    private String account() {
        return "Bearer " + accountTokens.issue(7L).value();
    }

    @Test
    void should_list_the_accounts_names_with_how_many_may_be_kept_and_never_cache_them() throws Exception {
        when(passengers.list(7L)).thenReturn(List.of(new SavedPassengers.Saved(3L, "Priya", "Perera")));

        mvc.perform(get(URL).header("Authorization", account())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.passengers[0].id").value(3))
                .andExpect(jsonPath("$.passengers[0].firstName").value("Priya"))
                .andExpect(jsonPath("$.passengers[0].lastName").value("Perera"));
    }

    @Test
    void should_save_a_name_for_the_account_in_the_token_and_answer_201_for_a_new_one_and_200_for_one_already_kept() throws Exception {
        when(passengers.add(7L, "Priya", "Perera"))
                .thenReturn(new SavedPassengerService.Added(new SavedPassengers.Saved(3L, "Priya", "Perera"), true))
                .thenReturn(new SavedPassengerService.Added(new SavedPassengers.Saved(3L, "Priya", "Perera"), false));

        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(3));
        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(3));
        verify(passengers, org.mockito.Mockito.times(2)).add(7L, "Priya", "Perera");
    }

    @Test
    void should_answer_409_when_the_places_are_full_and_400_for_a_name_that_will_not_do() throws Exception {
        when(passengers.add(anyLong(), anyString(), anyString())).thenThrow(new SavedPassengerLimitException("Ten names are already saved"));
        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Saved passengers full"));

        doThrow(new InvalidBookingException("A name is needed")).when(passengers).add(anyLong(), anyString(), anyString());   // doThrow: the stub above already throws
        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\" \",\"lastName\":\"Perera\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_answer_400_for_a_missing_or_too_long_name_without_calling_the_service() throws Exception {
        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Priya\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).header("Authorization", account()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"%s\",\"lastName\":\"Perera\"}".formatted("x".repeat(51)))).andExpect(status().isBadRequest());

        verifyNoInteractions(passengers);
    }

    @Test
    void should_remove_a_name_and_answer_404_for_one_that_is_not_there_or_not_theirs() throws Exception {
        mvc.perform(delete(URL + "/3").header("Authorization", account())).andExpect(status().isNoContent());
        verify(passengers).remove(7L, 3L);

        doThrow(new SavedPassengerNotFoundException("No saved passenger matches")).when(passengers).remove(7L, 4L);
        mvc.perform(delete(URL + "/4").header("Authorization", account())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Saved passenger not found"));
    }

    @Test
    void should_refuse_nobody_a_booking_token_and_a_staff_token_and_touch_nothing() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get(URL), post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY), delete(URL + "/3")}) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        String manage = "Bearer " + manageTokens.issue("ABC234", 0).value();
        String staff = "Bearer " + staffTokens.issue("priya.ops", StaffRole.ADMIN).value();
        for (String token : new String[] {manage, staff}) {
            mvc.perform(get(URL).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post(URL).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
            mvc.perform(delete(URL + "/3").header("Authorization", token)).andExpect(status().isForbidden());
        }

        verifyNoInteractions(passengers);
    }
}
