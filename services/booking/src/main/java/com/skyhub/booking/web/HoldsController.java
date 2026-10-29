package com.skyhub.booking.web;

import com.skyhub.booking.api.HoldsApi;
import com.skyhub.booking.api.model.CreateHoldRequestDto;
import com.skyhub.booking.api.model.HoldDto;
import com.skyhub.booking.application.HoldService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HoldsController implements HoldsApi {

    private final HoldService holdService;

    public HoldsController(HoldService holdService) {
        this.holdService = holdService;
    }

    @Override
    public ResponseEntity<HoldDto> createHold(CreateHoldRequestDto request) {
        HoldDto body = BookingDtoMapper.toDto(holdService.createHold(BookingDtoMapper.toCommand(request)));
        return ResponseEntity.created(URI.create("/api/v1/holds/" + body.getHoldId())).body(body);
    }

    @Override
    public ResponseEntity<Void> releaseHold(UUID holdId) {
        holdService.releaseHold(holdId.toString());
        return ResponseEntity.noContent().build();
    }
}
