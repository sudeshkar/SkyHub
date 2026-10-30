package com.skyhub.booking.web;

import com.skyhub.booking.api.AccountsApi;
import com.skyhub.booking.api.model.AccountDto;
import com.skyhub.booking.api.model.AccountLoginRequestDto;
import com.skyhub.booking.api.model.AccountSessionDto;
import com.skyhub.booking.api.model.PasswordLinkRequestDto;
import com.skyhub.booking.api.model.SetPasswordRequestDto;
import com.skyhub.booking.application.AccountService;
import com.skyhub.booking.application.AccountSession;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking for a link, choosing a password and signing in are open to everyone (they are how a person gets in); reading the account
 * needs an account token, checked in SecurityConfig before this runs. Responses carry credentials, so they are never cached.
 */
@RestController
public class AccountsController implements AccountsApi {

    private final AccountService accounts;

    public AccountsController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<Void> requestPasswordLink(PasswordLinkRequestDto request) {
        accounts.requestPasswordLink(request.getEmail());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> setAccountPassword(SetPasswordRequestDto request) {
        accounts.setPassword(request.getToken(), request.getPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<AccountSessionDto> openAccountSession(AccountLoginRequestDto request) {
        AccountSession session = accounts.login(request.getEmail(), request.getPassword());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new AccountSessionDto(session.token().value(), "Bearer", session.token().expiresAt().atOffset(ZoneOffset.UTC),
                        session.email()));
    }

    @Override
    public ResponseEntity<AccountDto> getMyAccount() {
        long id = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AccountDto(accounts.emailOf(id)));
    }
}
