package com.skyhub.booking.web;

import com.skyhub.booking.api.AccountEmailApi;
import com.skyhub.booking.api.model.EmailChangeConfirmationDto;
import com.skyhub.booking.api.model.EmailChangeReversalDto;
import com.skyhub.booking.api.model.EmailChangeRequestDto;
import com.skyhub.booking.application.AccountService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first two need an account token, checked in SecurityConfig before this runs; the account is the token's subject, never
 * something the caller can name (ADR-0039). Asking always answers 202, and nothing says whether the new address is taken.
 */
@RestController
public class AccountEmailController implements AccountEmailApi {

    private final AccountService accounts;

    public AccountEmailController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<Void> requestEmailChange(EmailChangeRequestDto request) {
        accounts.requestEmailChange(accountId(), request.getNewEmail(), request.getPassword());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> confirmEmailChange(EmailChangeConfirmationDto request) {
        accounts.confirmEmailChange(accountId(), request.getToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** The one endpoint here that needs no account token: the token from the old address's notice is the proof (ADR-0054). */
    @Override
    public ResponseEntity<Void> undoEmailChange(EmailChangeReversalDto request) {
        accounts.undoEmailChange(request.getToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static long accountId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
