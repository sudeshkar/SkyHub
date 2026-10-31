package com.skyhub.booking.web;

import com.skyhub.booking.api.CheckInApi;
import com.skyhub.booking.api.model.BoardingPassVerificationDto;
import com.skyhub.booking.api.model.BoardingPassVerificationRequestDto;
import com.skyhub.booking.api.model.BoardingPassesDto;
import com.skyhub.booking.api.model.CheckInRequestDto;
import com.skyhub.booking.application.BoardingPassVerifier;
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
    private final BoardingPassVerifier verifier;

    public CheckInController(CheckInService checkIn, BoardingPassVerifier verifier) {
        this.checkIn = checkIn;
        this.verifier = verifier;
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

    /** Asking again, or for a flight that was never checked in, is the same 204: the end state is what was asked for. */
    @Override
    public ResponseEntity<Void> checkOut(String locator, Integer segmentIndex) {
        checkIn.checkOut(locator, segmentIndex);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** A staff check (SecurityConfig): a refused pass is still a 200, because "not valid" is the answer, not an error. */
    @Override
    public ResponseEntity<BoardingPassVerificationDto> verifyBoardingPass(BoardingPassVerificationRequestDto request) {
        BoardingPassVerifier.Verdict verdict = verifier.verify(request.getBarcode());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BookingDtoMapper.toDto(verdict));
    }

    private static ResponseEntity<BoardingPassesDto> respond(CheckInResult result, HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(BookingDtoMapper.toDto(result));
    }
}
