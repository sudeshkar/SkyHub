package com.skyhub.booking.web;

import com.skyhub.booking.api.BulkRebookingApi;
import com.skyhub.booking.api.model.BulkRebookRequestDto;
import com.skyhub.booking.api.model.BulkRebookedDto;
import com.skyhub.booking.api.model.MoveResultDto;
import com.skyhub.booking.application.rebooking.BulkRebookingService;
import com.skyhub.booking.application.rebooking.MoveResult;
import com.skyhub.booking.application.rebooking.RebookCommand;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before this runs (SecurityConfig): a staff token with a role that may rebook. As for a single move, the
 * staff member's name comes from the verified token, never from the request, and is what each audit record stores.
 */
@RestController
public class BulkRebookingController implements BulkRebookingApi {

    private final BulkRebookingService bulk;

    public BulkRebookingController(BulkRebookingService bulk) {
        this.bulk = bulk;
    }

    @Override
    public ResponseEntity<BulkRebookedDto> rebookFlights(BulkRebookRequestDto request) {
        String staff = SecurityContextHolder.getContext().getAuthentication().getName();
        var moves = request.getMoves().stream()
                .map(m -> new RebookCommand(m.getLocator(), m.getSegmentIndex(), m.getFlightNumber(), m.getDepartureDate(), staff))
                .toList();
        var results = bulk.rebookAll(moves).stream().map(BulkRebookingController::toDto).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BulkRebookedDto(results));
    }

    private static MoveResultDto toDto(MoveResult result) {
        return new MoveResultDto(result.locator(), result.segmentIndex(), MoveResultDto.OutcomeEnum.valueOf(result.outcome().name()))
                .detail(result.detail()).seats(result.seats());
    }
}
