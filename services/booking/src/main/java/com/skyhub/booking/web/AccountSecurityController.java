package com.skyhub.booking.web;

import com.skyhub.booking.api.AccountSecurityApi;
import com.skyhub.booking.application.AccountService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Needs an account token, checked in SecurityConfig before this runs; the account is the token's subject, never something the caller
 * can name (ADR-0047). Ending sessions can only make the account harder to use, so it asks for nothing more than the token.
 */
@RestController
public class AccountSecurityController implements AccountSecurityApi {

    private final AccountService accounts;

    public AccountSecurityController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<Void> endAllAccountSessions() {
        accounts.signOutEverywhere(Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
