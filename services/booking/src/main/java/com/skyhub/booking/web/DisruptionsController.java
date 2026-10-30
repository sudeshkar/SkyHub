package com.skyhub.booking.web;

import com.skyhub.booking.api.DisruptionsApi;
import com.skyhub.booking.api.model.AffectedBookingsDto;
import com.skyhub.booking.api.model.BookingDisruptionsDto;
import com.skyhub.booking.api.model.DisruptionRecordedDto;
import com.skyhub.booking.api.model.RecordDisruptionRequestDto;
import com.skyhub.booking.application.DisruptionService;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before these methods run (SecurityConfig): recording needs a staff token with a role that may record,
 * reading needs a manage token for the booking in the URL.
 */
@RestController
public class DisruptionsController implements DisruptionsApi {

    private final DisruptionService disruptions;

    public DisruptionsController(DisruptionService disruptions) {
        this.disruptions = disruptions;
    }

    @Override
    public ResponseEntity<DisruptionRecordedDto> recordDisruption(RecordDisruptionRequestDto request) {
        String staff = SecurityContextHolder.getContext().getAuthentication().getName();
        int affected = disruptions.record(BookingDtoMapper.toCommand(request, staff));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DisruptionRecordedDto(affected));
    }

    @Override
    public ResponseEntity<AffectedBookingsDto> getAffectedBookings(String flightNumber, LocalDate departureDate) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BookingDtoMapper.toAffectedDto(flightNumber, departureDate, disruptions.affectedBookings(flightNumber, departureDate)));
    }

    @Override
    public ResponseEntity<BookingDisruptionsDto> getDisruptions(String locator) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BookingDtoMapper.toDto(disruptions.forBooking(locator)));
    }
}
