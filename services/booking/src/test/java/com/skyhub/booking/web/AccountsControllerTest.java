package com.skyhub.booking.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountLoginFailedException;
import com.skyhub.booking.application.AccountService;
import com.skyhub.booking.application.AccountSession;
import com.skyhub.booking.application.AccountToken;
import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.PasswordLinkInvalidException;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.InvalidAccountException;
import com.skyhub.booking.domain.StaffRole;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountsController.class)
@Import(WebSliceSecurity.class)
class AccountsControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountTokenIssuer accountTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @MockitoBean
    private AccountService accounts;

    private static String json(String body) {
        return body;
    }

    @Test
    void should_accept_a_link_request_with_202_and_say_nothing_else() throws Exception {
        mvc.perform(post("/api/v1/accounts/password-links").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"priya@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(accounts).requestPasswordLink("priya@example.com");
    }

    @Test
    void should_answer_400_for_a_bad_address_a_bad_link_and_a_weak_password() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidAccountException("Enter a valid email address")).when(accounts).requestPasswordLink("nope");
        org.mockito.Mockito.doThrow(new PasswordLinkInvalidException()).when(accounts).setPassword("old", "a long enough passphrase");

        mvc.perform(post("/api/v1/accounts/password-links").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Enter a valid email address"));
        mvc.perform(post("/api/v1/accounts/passwords").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"old\",\"password\":\"a long enough passphrase\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Link not valid"));
        mvc.perform(post("/api/v1/accounts/passwords").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());   // the contract's minLength
    }

    @Test
    void should_set_a_password_with_204() throws Exception {
        mvc.perform(post("/api/v1/accounts/passwords").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc\",\"password\":\"a long enough passphrase\"}"))
                .andExpect(status().isNoContent());

        verify(accounts).setPassword("abc", "a long enough passphrase");
    }

    @Test
    void should_sign_in_with_201_and_never_cache_the_token() throws Exception {
        when(accounts.login("priya@example.com", "a long enough passphrase"))
                .thenReturn(new AccountSession(new AccountToken("jwt", Instant.parse("2026-11-02T10:30:00Z")), "priya@example.com"));

        mvc.perform(post("/api/v1/accounts/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"priya@example.com\",\"password\":\"a long enough passphrase\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("jwt"))
                .andExpect(jsonPath("$.email").value("priya@example.com"));
    }

    @Test
    void should_answer_401_for_any_sign_in_failure() throws Exception {
        when(accounts.login(any(), any())).thenThrow(new AccountLoginFailedException());

        mvc.perform(post("/api/v1/accounts/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"priya@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.title").value("Sign-in failed"));
    }

    @Test
    void should_show_the_account_only_to_a_holder_of_an_account_token() throws Exception {
        when(accounts.emailOf(7L)).thenReturn("priya@example.com");

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + accountTokens.issue(7L).value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("priya@example.com"));
        mvc.perform(get("/api/v1/accounts/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + manageTokens.issue("ABC234").value()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + staffTokens.issue("priya.ops", StaffRole.ADMIN).value()))
                .andExpect(status().isForbidden());
    }

    @Test
    void should_not_let_an_account_token_do_what_only_staff_or_a_booking_holder_may() throws Exception {
        String account = "Bearer " + accountTokens.issue(7L).value();

        mvc.perform(post("/api/v1/ops/staff").header("Authorization", account).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x.y.z\",\"password\":\"a long enough passphrase\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pnrs/ABC234").header("Authorization", account)).andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
    }
}
