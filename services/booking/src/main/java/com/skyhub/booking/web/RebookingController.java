package com.skyhub.booking.web;

import com.skyhub.booking.api.RebookingApi;
import com.skyhub.booking.api.model.RebookRequestDto;
import com.skyhub.booking.api.model.RebookedDto;
import com.skyhub.booking.api.model.SegmentDto;
import com.skyhub.booking.application.rebooking.RebookCommand;
import com.skyhub.booking.application.rebooking.RebookingResult;
import com.skyhub.booking.application.rebooking.RebookingService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before this runs (SecurityConfig): a staff token with a role that may rebook. The staff member's
 * name comes from the verified token, never from the request, and is what the audit record stores.
 */
@RestController
public class RebookingController implements RebookingApi {

    private final RebookingService rebooking;

    public RebookingController(RebookingService rebooking) {
        this.rebooking = rebooking;
    }

    @Override
    public ResponseEntity<RebookedDto> rebookFlight(String locator, RebookRequestDto request) {
        String staff = SecurityContextHolder.getContext().getAuthentication().getName();
        RebookingResult result = rebooking.rebook(new RebookCommand(locator, request.getSegmentIndex(),
                request.getFlightNumber(), request.getDepartureDate(), staff));
        RebookedDto body = new RebookedDto(result.locator(), result.segmentIndex(),
                new SegmentDto(result.from().flightNumber(), result.from().departureDate()),
                new SegmentDto(result.to().flightNumber(), result.to().departureDate()), result.seats(), result.changed());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
