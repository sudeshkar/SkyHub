package com.skyhub.notification.application;

import com.skyhub.notification.application.NotificationMetrics.Outcome;
import com.skyhub.notification.domain.BookingConfirmedEvent;
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
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> handle(event));
        } catch (RuntimeException failed) {
            metrics.record(BookingConfirmedEvent.TYPE, Outcome.FAILED);
            log.atWarn().addKeyValue("event.id", event.eventId()).addKeyValue("pnr.locator", event.locator())
                    .setCause(failed).log("Confirmation email not sent; it will be retried");
            throw failed;
        }
        metrics.record(BookingConfirmedEvent.TYPE, outcome);
    }

    private Outcome handle(BookingConfirmedEvent event) {
        if (sent.existsByEventId(event.eventId())) {
            log.atInfo().addKeyValue("event.id", event.eventId()).log("Event already handled; skipped");
            return Outcome.DUPLICATE;
        }
        EmailMessage message = EmailComposer.bookingConfirmed(event);
        // Claim first and flush: a concurrent duplicate hits the unique constraint here, before anything is sent.
        sent.saveAndFlush(SentNotification.of(event.eventId(), BookingConfirmedEvent.TYPE, event.locator(),
                message.maskedTo(), message.subject(), clock.instant()));
        email.send(message);
        return Outcome.SENT;
    }
}
