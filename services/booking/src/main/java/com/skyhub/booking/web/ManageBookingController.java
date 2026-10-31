package com.skyhub.booking.web;

import com.skyhub.booking.api.ManageBookingApi;
import com.skyhub.booking.api.model.ManageSessionDto;
import com.skyhub.booking.api.model.ManageSessionRequestDto;
import com.skyhub.booking.api.model.PnrDto;
import com.skyhub.booking.api.model.SeatChangeDto;
import com.skyhub.booking.application.ManageBookingService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before these methods run (SecurityConfig): every endpoint except
 * {@code openManageSession} needs a token issued for the locator in the URL.
 * Responses hold personal data or credentials, so they are never cached.
 */
@RestController
public class ManageBookingController implements ManageBookingApi {

    private final ManageBookingService manageBooking;

    public ManageBookingController(ManageBookingService manageBooking) {
        this.manageBooking = manageBooking;
    }

    @Override
    public ResponseEntity<ManageSessionDto> openManageSession(String locator, ManageSessionRequestDto request) {
        ManageSessionDto body = BookingDtoMapper.toDto(manageBooking.openSession(locator, request.getLastName()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    /** Signing out: the token used for this call is refused from now on, like every other token of the booking (ADR-0057). */
    @Override
    public ResponseEntity<Void> endManageSessions(String locator) {
        manageBooking.endSessions(locator);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<PnrDto> getPnr(String locator) {
        return noStore(BookingDtoMapper.toDto(manageBooking.find(locator)));
    }

    @Override
    public ResponseEntity<PnrDto> cancelPnr(String locator) {
        return noStore(BookingDtoMapper.toDto(manageBooking.cancel(locator)));
    }

    @Override
    public ResponseEntity<PnrDto> changeSeat(String locator, SeatChangeDto request) {
        return noStore(BookingDtoMapper.toDto(manageBooking.changeSeat(locator, BookingDtoMapper.toCommand(request))));
    }

    private static ResponseEntity<PnrDto> noStore(PnrDto body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
