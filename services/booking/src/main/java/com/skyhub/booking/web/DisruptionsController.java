package com.skyhub.booking.web;

import com.skyhub.booking.api.DisruptionsApi;
import com.skyhub.booking.api.model.BookingDisruptionsDto;
import com.skyhub.booking.api.model.DisruptionRecordedDto;
import com.skyhub.booking.api.model.RecordDisruptionRequestDto;
import com.skyhub.booking.application.DisruptionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before these methods run (SecurityConfig): recording needs the operations key,
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
        int affected = disruptions.record(BookingDtoMapper.toCommand(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new DisruptionRecordedDto(affected));
    }

    @Override
    public ResponseEntity<BookingDisruptionsDto> getDisruptions(String locator) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BookingDtoMapper.toDto(disruptions.forBooking(locator)));
    }
}
