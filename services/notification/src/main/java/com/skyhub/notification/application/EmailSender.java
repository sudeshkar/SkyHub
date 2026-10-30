package com.skyhub.notification.application;

/**
 * Port: sends one email. The rules never know whether it goes to a log, a test mail server or a real provider
 * (ADR-0019). It throws {@link EmailUnavailableException} when the message could not be handed over, so the event
 * is retried rather than recorded as sent.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
