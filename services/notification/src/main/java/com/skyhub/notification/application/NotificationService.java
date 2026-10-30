package com.skyhub.notification.application;

import com.skyhub.notification.application.NotificationMetrics.Outcome;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.NotificationEvent;
import com.skyhub.notification.domain.SentNotification;
import com.skyhub.notification.persistence.SentNotificationRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Turns events into emails, once each (ADR-0019). Delivery from Kafka is at least once, so every event is handled
 * in one transaction that first <em>claims</em> its id (a unique row) and only then sends. A redelivery finds the
 * claim and stops; a failed send rolls the claim back, so the retry starts clean.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SentNotificationRepository sent;
    private final EmailSender email;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final NotificationMetrics metrics;

    public NotificationService(SentNotificationRepository sent, EmailSender email, Clock clock,
                               TransactionOperations transactions, NotificationMetrics metrics) {
        this.sent = sent;
        this.email = email;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public void bookingConfirmed(BookingConfirmedEvent event) {
        deliver(event, EmailComposer.bookingConfirmed(event));
    }

    public void bookingCancelled(BookingCancelledEvent event) {
        deliver(event, EmailComposer.bookingCancelled(event));
    }

    public void bookingDisrupted(BookingDisruptedEvent event) {
        deliver(event, EmailComposer.bookingDisrupted(event));
    }

    public void bookingRebooked(BookingRebookedEvent event) {
        deliver(event, EmailComposer.bookingRebooked(event));
    }

    private void deliver(NotificationEvent event, EmailMessage message) {
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> handle(event, message));
        } catch (RuntimeException failed) {
            metrics.record(event.type(), Outcome.FAILED);
            log.atWarn().addKeyValue("event.id", event.eventId()).addKeyValue("event.type", event.type())
                    .addKeyValue("pnr.locator", event.locator()).setCause(failed)
                    .log("Email not sent; it will be retried");
            throw failed;
        }
        metrics.record(event.type(), outcome);
    }

    private Outcome handle(NotificationEvent event, EmailMessage message) {
        if (sent.existsByEventId(event.eventId())) {
            log.atInfo().addKeyValue("event.id", event.eventId()).log("Event already handled; skipped");
            return Outcome.DUPLICATE;
        }
        // Claim first and flush: a concurrent duplicate hits the unique constraint here, before anything is sent.
        sent.saveAndFlush(SentNotification.of(event.eventId(), event.type(), event.locator(), message.maskedTo(),
                message.subject(), clock.instant()));
        email.send(message);
        return Outcome.SENT;
    }
}
