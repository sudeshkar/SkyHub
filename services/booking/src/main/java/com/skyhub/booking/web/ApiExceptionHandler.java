package com.skyhub.booking.web;

import com.skyhub.booking.application.FlightCatalogUnavailableException;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.application.AccountLoginFailedException;
import com.skyhub.booking.application.PasswordLinkInvalidException;
import com.skyhub.booking.application.PasswordNotAcceptedException;
import com.skyhub.booking.application.StaffLoginFailedException;
import com.skyhub.booking.domain.InvalidAccountException;
import com.skyhub.booking.application.rebooking.RebookingRefusedException;
import com.skyhub.booking.application.StaffUsernameTakenException;
import com.skyhub.booking.domain.InvalidStaffAccountException;
import com.skyhub.booking.domain.AlreadyCheckedInException;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.CheckInClosedException;
import com.skyhub.booking.domain.NotCheckedInException;
import com.skyhub.booking.domain.PaymentDeclinedException;
import com.skyhub.booking.domain.PriceChangedException;
import com.skyhub.booking.application.WithdrawalRefusedException;
import com.skyhub.booking.domain.DisruptionNotFoundException;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.domain.FlightNotFoundException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(BookingCancelledException.class)
    ProblemDetail handleCancelled(BookingCancelledException ex) {
        return problem(HttpStatus.CONFLICT, "Booking cancelled", ex.getMessage());
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
        return problem(HttpStatus.PAYMENT_REQUIRED, "Payment declined", ex.getMessage());
    }

    @ExceptionHandler(PriceChangedException.class)
    ProblemDetail handlePriceChanged(PriceChangedException ex) {
        return problem(HttpStatus.CONFLICT, "Price changed", ex.getMessage());
    }

    /** The provider could not be reached. Nothing was charged and nothing was booked; the client may retry. */
    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ProblemDetail> handlePaymentUnavailable(PaymentUnavailableException ex) {
        log.warn("Payment provider unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Payment unavailable",
                        "We couldn't reach the payment provider. You have not been charged. Please try again shortly."));
    }

    @ExceptionHandler(CheckInClosedException.class)
    ProblemDetail handleCheckInClosed(CheckInClosedException ex) {
        return problem(HttpStatus.CONFLICT, "Check-in unavailable", ex.getMessage());
    }

    @ExceptionHandler(FlightCancelledException.class)
    ProblemDetail handleFlightCancelled(FlightCancelledException ex) {
        return problem(HttpStatus.CONFLICT, "Flight cancelled", ex.getMessage());
    }

    @ExceptionHandler(AlreadyCheckedInException.class)
    ProblemDetail handleAlreadyCheckedIn(AlreadyCheckedInException ex) {
        return problem(HttpStatus.CONFLICT, "Already checked in", ex.getMessage());
    }

    @ExceptionHandler(NotCheckedInException.class)
    ProblemDetail handleNotCheckedIn(NotCheckedInException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not checked in", ex.getMessage());
    }

    @ExceptionHandler(DisruptionNotFoundException.class)
    ProblemDetail handleDisruptionNotFound(DisruptionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Disruption not found", ex.getMessage());
    }

    @ExceptionHandler(WithdrawalRefusedException.class)
    ProblemDetail handleWithdrawalRefused(WithdrawalRefusedException ex) {
        return problem(HttpStatus.CONFLICT, "Disruption cannot be withdrawn", ex.getMessage());
    }

    @ExceptionHandler(FlightNotFoundException.class)
    ProblemDetail handleFlightNotFound(FlightNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Flight not found", ex.getMessage());
    }

    /** The timetable could not be checked. Nothing was created; the client may retry shortly. */
    @ExceptionHandler(FlightCatalogUnavailableException.class)
    ResponseEntity<ProblemDetail> handleCatalogUnavailable(FlightCatalogUnavailableException ex) {
        log.warn("Flight catalog unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Schedule unavailable",
                        "We couldn't check the flight schedule. Please try again shortly."));
    }

    @ExceptionHandler(InvalidAccountException.class)
    ProblemDetail handleInvalidAccount(InvalidAccountException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid account request", ex.getMessage());
    }

    @ExceptionHandler(PasswordLinkInvalidException.class)
    ProblemDetail handlePasswordLinkInvalid(PasswordLinkInvalidException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Link not valid", ex.getMessage());
    }

    /** One answer for every sign-in failure, so nothing says which addresses have accounts (ADR-0033). */
    @ExceptionHandler(AccountLoginFailedException.class)
    ProblemDetail handleAccountLoginFailed(AccountLoginFailedException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Sign-in failed", ex.getMessage());
    }

    /** A wrong, missing or locked password when changing the address: 403, so a mistyped password is not taken for an ended session. */
    @ExceptionHandler(PasswordNotAcceptedException.class)
    ProblemDetail handlePasswordNotAccepted(PasswordNotAcceptedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Password not accepted", ex.getMessage());
    }

    /** One answer for every login failure, so nothing says which usernames exist (ADR-0017). */
    @ExceptionHandler(StaffLoginFailedException.class)
    ProblemDetail handleStaffLoginFailed(StaffLoginFailedException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Login failed", ex.getMessage());
    }

    @ExceptionHandler(StaffUsernameTakenException.class)
    ProblemDetail handleStaffUsernameTaken(StaffUsernameTakenException ex) {
        return problem(HttpStatus.CONFLICT, "Username taken", ex.getMessage());
    }

    @ExceptionHandler(InvalidStaffAccountException.class)
    ProblemDetail handleInvalidStaff(InvalidStaffAccountException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid staff account", ex.getMessage());
    }

    /** A rule forbids this move. The message says which and why; it holds no personal data. */
    @ExceptionHandler(RebookingRefusedException.class)
    ProblemDetail handleRebookingRefused(RebookingRefusedException ex) {
        return problem(HttpStatus.CONFLICT, "Rebooking refused", ex.getMessage());
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
