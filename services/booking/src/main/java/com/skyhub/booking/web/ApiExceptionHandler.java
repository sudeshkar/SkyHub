package com.skyhub.booking.web;

import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.SeatUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem details. Messages are written for API clients:
 * no stack traces, SQL, class names or other people's data ever reach a response.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidBookingException.class)
    ProblemDetail handleInvalidBooking(InvalidBookingException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid booking", ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(SeatUnavailableException.class)
    ProblemDetail handleSeatUnavailable(SeatUnavailableException ex) {
        return problem(HttpStatus.CONFLICT, "Seat unavailable", ex.getMessage());
    }

    /** Another booking took a seat between our read and our write (ADR-0002). */
    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail handleLockConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Seat unavailable",
                "One of the selected seats was just taken. Please choose another seat.");
    }

    /** Concurrent duplicate submission (same Idempotency-Key) or a locator collision. Safe to retry. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrityConflict(DataIntegrityViolationException ex) {
        log.warn("Booking write conflict", ex);
        return problem(HttpStatus.CONFLICT, "Request conflict", "This booking could not be saved. Please try again.");
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused", ex.getMessage());
    }

    @ExceptionHandler(PnrNotFoundException.class)
    ProblemDetail handleNotFound(PnrNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Booking not found", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "Something went wrong. Please try again.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
