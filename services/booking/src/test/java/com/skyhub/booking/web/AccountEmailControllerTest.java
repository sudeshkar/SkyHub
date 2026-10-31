package com.skyhub.booking.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountLoginFailedException;
import com.skyhub.booking.application.AccountService;
import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.PasswordLinkInvalidException;
import com.skyhub.booking.application.PasswordNotAcceptedException;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.InvalidAccountException;
import com.skyhub.booking.domain.StaffRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(AccountEmailController.class)
@Import(WebSliceSecurity.class)
class AccountEmailControllerTest {

    private static final String ASK = "/api/v1/accounts/me/email-change";
    private static final String CONFIRM = "/api/v1/accounts/me/email-change/confirmation";
    private static final String ASK_BODY = "{\"newEmail\":\"priya.new@example.com\",\"password\":\"a long enough passphrase\"}";

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

    private String account() {
        return "Bearer " + accountTokens.issue(7L).value();
    }

    private static MockHttpServletRequestBuilder json(String path, String body, String bearer) {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    @Test
    void should_accept_a_request_with_202_for_the_account_in_the_token_and_say_nothing_else() throws Exception {
        mvc.perform(json(ASK, ASK_BODY, account()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(accounts).requestEmailChange(7L, "priya.new@example.com", "a long enough passphrase");
    }

    @Test
    void should_answer_403_not_401_for_a_wrong_password_so_the_session_is_not_taken_for_ended() throws Exception {
        doThrow(new PasswordNotAcceptedException()).when(accounts).requestEmailChange(7L, "priya.new@example.com", "a long enough passphrase");

        mvc.perform(json(ASK, ASK_BODY, account()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Password not accepted"));
    }

    @Test
    void should_answer_400_with_the_reason_for_an_address_that_is_not_one() throws Exception {
        doThrow(new InvalidAccountException("Enter a valid email address")).when(accounts).requestEmailChange(7L, "nope", "a long enough passphrase");

        mvc.perform(json(ASK, "{\"newEmail\":\"nope\",\"password\":\"a long enough passphrase\"}", account()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter a valid email address"));
    }

    @Test
    void should_refuse_a_body_with_a_missing_field_before_the_service_is_asked() throws Exception {
        mvc.perform(json(ASK, "{\"newEmail\":\"priya.new@example.com\"}", account())).andExpect(status().isBadRequest());
        mvc.perform(json(ASK, "{\"password\":\"x\"}", account())).andExpect(status().isBadRequest());

        verifyNoInteractions(accounts);
    }

    @Test
    void should_confirm_with_204_for_the_account_in_the_token() throws Exception {
        mvc.perform(json(CONFIRM, "{\"token\":\"abc\"}", account()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(accounts).confirmEmailChange(7L, "abc");
    }

    @Test
    void should_answer_400_with_the_same_title_as_an_account_link_for_a_bad_token() throws Exception {
        doThrow(new PasswordLinkInvalidException()).when(accounts).confirmEmailChange(7L, "old");

        mvc.perform(json(CONFIRM, "{\"token\":\"old\"}", account()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Link not valid"));
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() throws Exception {
        doThrow(new AccountLoginFailedException()).when(accounts).confirmEmailChange(7L, "abc");

        mvc.perform(json(CONFIRM, "{\"token\":\"abc\"}", account())).andExpect(status().isUnauthorized());
    }

    @Test
    void should_serve_only_a_holder_of_an_account_token() throws Exception {
        String manage = "Bearer " + manageTokens.issue("ABC234", 0).value();
        String staff = "Bearer " + staffTokens.issue("priya.ops", StaffRole.ADMIN).value();

        mvc.perform(json(ASK, ASK_BODY, null)).andExpect(status().isUnauthorized());
        mvc.perform(json(ASK, ASK_BODY, manage)).andExpect(status().isForbidden());
        mvc.perform(json(ASK, ASK_BODY, staff)).andExpect(status().isForbidden());
        mvc.perform(json(CONFIRM, "{\"token\":\"abc\"}", null)).andExpect(status().isUnauthorized());
        mvc.perform(json(CONFIRM, "{\"token\":\"abc\"}", manage)).andExpect(status().isForbidden());
        mvc.perform(json(CONFIRM, "{\"token\":\"abc\"}", staff)).andExpect(status().isForbidden());

        verifyNoInteractions(accounts);
    }
}
