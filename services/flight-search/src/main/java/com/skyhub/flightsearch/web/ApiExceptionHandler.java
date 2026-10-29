package com.skyhub.flightsearch.web;

import com.skyhub.flightsearch.domain.FlightNotFoundException;
import com.skyhub.flightsearch.domain.InvalidSearchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem details. Spring's base class already covers missing parameters,
 * type mismatches and built-in method validation.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidSearchException.class)
    ProblemDetail handleInvalidSearch(InvalidSearchException ex) {
        return problem("Invalid search", ex.getMessage());
    }

    @ExceptionHandler(FlightNotFoundException.class)
    ProblemDetail handleFlightNotFound(FlightNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Flight not found");
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return problem("Invalid request", ex.getMessage());
    }

    private static ProblemDetail problem(String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(title);
        return problem;
    }
}
