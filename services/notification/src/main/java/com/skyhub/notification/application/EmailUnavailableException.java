package com.skyhub.notification.application;

/** The mail server did not accept the message. Retrying later may work. */
public class EmailUnavailableException extends RuntimeException {

    public EmailUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
