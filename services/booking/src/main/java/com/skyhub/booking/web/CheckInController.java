package com.skyhub.booking.web;

import com.skyhub.booking.api.CheckInApi;
import com.skyhub.booking.api.model.BoardingPassesDto;
import com.skyhub.booking.api.model.CheckInRequestDto;
import com.skyhub.booking.application.CheckInResult;
import com.skyhub.booking.application.CheckInService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorisation happens before these methods run (SecurityConfig): the token must be for the locator in the URL.
 * Boarding passes carry a passenger's name, so they are never cached.
 */
@RestController
public class CheckInController implements CheckInApi {

    private final CheckInService checkIn;

    public CheckInController(CheckInService checkIn) {
        this.checkIn = checkIn;
    }

    @Override
    public ResponseEntity<BoardingPassesDto> checkIn(String locator, CheckInRequestDto request) {
        CheckInResult result = checkIn.checkIn(locator, request.getSegmentIndex());
        return respond(result, result.created() ? HttpStatus.CREATED : HttpStatus.OK);
    }

    @Override
    public ResponseEntity<BoardingPassesDto> getBoardingPasses(String locator, Integer segmentIndex) {
        return respond(checkIn.boardingPasses(locator, segmentIndex), HttpStatus.OK);
    }

    private static ResponseEntity<BoardingPassesDto> respond(CheckInResult result, HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(BookingDtoMapper.toDto(result));
    }
}
