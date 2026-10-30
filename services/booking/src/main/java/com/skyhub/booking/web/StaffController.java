package com.skyhub.booking.web;

import com.skyhub.booking.api.StaffApi;
import com.skyhub.booking.api.model.CreateStaffRequestDto;
import com.skyhub.booking.api.model.StaffAccountDto;
import com.skyhub.booking.api.model.StaffLoginRequestDto;
import com.skyhub.booking.api.model.StaffRoleDto;
import com.skyhub.booking.api.model.StaffSessionDto;
import com.skyhub.booking.application.CreateStaffCommand;
import com.skyhub.booking.application.StaffAccountView;
import com.skyhub.booking.application.StaffService;
import com.skyhub.booking.application.StaffSession;
import com.skyhub.booking.domain.StaffRole;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login is open to everyone (it is how a token is obtained); creating accounts needs an ADMIN token, checked
 * in SecurityConfig before this runs. Responses carry credentials, so they are never cached.
 */
@RestController
public class StaffController implements StaffApi {

    private final StaffService staff;

    public StaffController(StaffService staff) {
        this.staff = staff;
    }

    @Override
    public ResponseEntity<StaffSessionDto> openStaffSession(StaffLoginRequestDto request) {
        StaffSession session = staff.login(request.getUsername(), request.getPassword());
        StaffSessionDto body = new StaffSessionDto(session.token().value(), "Bearer",
                session.token().expiresAt().atOffset(ZoneOffset.UTC), session.username(),
                StaffRoleDto.valueOf(session.role().name()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    @Override
    public ResponseEntity<StaffAccountDto> createStaffAccount(CreateStaffRequestDto request) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        StaffAccountView created = staff.create(actor, new CreateStaffCommand(request.getUsername(),
                request.getPassword(), StaffRole.valueOf(request.getRole().name())));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new StaffAccountDto(created.username(), StaffRoleDto.valueOf(created.role().name())));
    }
}
