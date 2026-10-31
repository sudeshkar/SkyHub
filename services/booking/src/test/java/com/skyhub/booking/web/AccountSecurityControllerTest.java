package com.skyhub.booking.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountLoginFailedException;
import com.skyhub.booking.application.AccountService;
import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.StaffRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountSecurityController.class)
@Import(WebSliceSecurity.class)
class AccountSecurityControllerTest {

    private static final String URL = "/api/v1/accounts/me/sessions";

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

    @Test
    void should_end_every_session_of_the_account_in_the_token_with_204_and_never_cache_it() throws Exception {
        mvc.perform(delete(URL).header("Authorization", account()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(accounts).signOutEverywhere(7L);
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() throws Exception {
        doThrow(new AccountLoginFailedException()).when(accounts).signOutEverywhere(7L);

        mvc.perform(delete(URL).header("Authorization", account())).andExpect(status().isUnauthorized());
    }

    @Test
    void should_serve_only_a_holder_of_an_account_token() throws Exception {
        mvc.perform(delete(URL)).andExpect(status().isUnauthorized());
        mvc.perform(delete(URL).header("Authorization", "Bearer " + manageTokens.issue("ABC234").value())).andExpect(status().isForbidden());
        mvc.perform(delete(URL).header("Authorization", "Bearer " + staffTokens.issue("priya.ops", StaffRole.ADMIN).value()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accounts);
    }
}
