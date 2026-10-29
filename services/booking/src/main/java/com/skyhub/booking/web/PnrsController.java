package com.skyhub.booking.web;

import com.skyhub.booking.api.PnrsApi;
import com.skyhub.booking.api.model.CreatePnrRequestDto;
import com.skyhub.booking.api.model.PnrDto;
import com.skyhub.booking.application.BookingResult;
import com.skyhub.booking.application.BookingService;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PnrsController implements PnrsApi {

    private final BookingService bookingService;

    public PnrsController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Override
    public ResponseEntity<PnrDto> createPnr(String idempotencyKey, CreatePnrRequestDto request) {
        BookingResult result = bookingService.createPnr(BookingDtoMapper.toCommand(request), idempotencyKey);
        PnrDto body = BookingDtoMapper.toDto(result.pnr());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/pnrs/" + body.getLocator())).body(body);
    }

    @Override
    public ResponseEntity<PnrDto> getPnr(String locator, String lastName) {
        return ResponseEntity.ok(BookingDtoMapper.toDto(bookingService.findPnr(locator, lastName)));
    }
}
