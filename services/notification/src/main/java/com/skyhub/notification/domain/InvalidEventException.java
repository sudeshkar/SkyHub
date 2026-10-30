package com.skyhub.notification.domain;

/** A message that cannot be an event we understand. Retrying cannot help, so it is parked, not retried. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
