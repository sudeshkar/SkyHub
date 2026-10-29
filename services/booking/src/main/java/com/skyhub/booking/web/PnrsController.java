package com.skyhub.booking.web;

import com.skyhub.booking.api.PnrsApi;
import com.skyhub.booking.api.model.CreatePnrRequestDto;
import com.skyhub.booking.api.model.PnrDto;
import com.skyhub.booking.api.model.QuoteDto;
import com.skyhub.booking.api.model.QuoteRequestDto;
import com.skyhub.booking.application.BookingResult;
import com.skyhub.booking.application.BookingService;
import com.skyhub.booking.application.PricingService;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PnrSegment;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PnrsController implements PnrsApi {

    private final BookingService bookingService;
    private final PricingService pricing;

    public PnrsController(BookingService bookingService, PricingService pricing) {
        this.bookingService = bookingService;
        this.pricing = pricing;
    }

    @Override
    public ResponseEntity<QuoteDto> quote(QuoteRequestDto request) {
        Money price = pricing.quote(
                request.getSegments().stream().map(s -> new PnrSegment(s.getFlightNumber(), s.getDepartureDate())).toList(),
                request.getPassengerCount());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new QuoteDto(price.amount(), price.currency()));
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

}
