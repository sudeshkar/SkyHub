package com.skyhub.notification.application;

import com.skyhub.notification.application.NotificationMetrics.Outcome;
import com.skyhub.notification.domain.AccountEmailChangeLinkEvent;
import com.skyhub.notification.domain.AccountEmailChangedEvent;
import com.skyhub.notification.domain.AccountPasswordLinkEvent;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingCheckInOpenEvent;
import com.skyhub.notification.domain.BookingCheckedInEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingDisruptionWithdrawnEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.BookingRefundCompletedEvent;
import com.skyhub.notification.domain.NotificationEvent;
import com.skyhub.notification.domain.SentNotification;
import com.skyhub.notification.persistence.SentNotificationRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final SecretOpener tokens;
    private final PassImages images;
    private final ETickets tickets;
    private final String siteUrl;

    public NotificationService(SentNotificationRepository sent, EmailSender email, Clock clock,
                               TransactionOperations transactions, NotificationMetrics metrics, SecretOpener tokens,
                               PassImages images, ETickets tickets) {
        this(sent, email, clock, transactions, metrics, tokens, images, tickets, "http://localhost:3000");
    }

    @Autowired
    public NotificationService(SentNotificationRepository sent, EmailSender email, Clock clock,
                               TransactionOperations transactions, NotificationMetrics metrics, SecretOpener tokens,
                               PassImages images, ETickets tickets,
                               @Value("${skyhub.notifications.site-url:http://localhost:3000}") String siteUrl) {
        this.tokens = tokens;
        this.images = images;
        this.tickets = tickets;
        this.siteUrl = siteUrl;
        this.sent = sent;
        this.email = email;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public void bookingConfirmed(BookingConfirmedEvent event) {
        List<EmailMessage.Attachment> ticket = Itinerary.of(event).map(trip -> eTicket(event, trip)).orElse(List.of());
        deliver(event, EmailComposer.bookingConfirmed(event, !ticket.isEmpty()).withAttachments(ticket));
    }

    /**
     * The e-ticket PDF (ADR-0087), or nothing: the confirmation is what matters, so a PDF that cannot be drawn sends the email without
     * it, counts it, and says so in the log with no name or address in it.
     */
    private List<EmailMessage.Attachment> eTicket(BookingConfirmedEvent event, Itinerary trip) {
        try {
            byte[] pdf = tickets.pdf(trip);
            metrics.eTickets("attached");
            return List.of(new EmailMessage.Attachment("SkyHub-e-ticket-" + event.locator() + ".pdf", "application/pdf", pdf));
        } catch (RuntimeException e) {
            metrics.eTickets("dropped");
            log.atWarn().addKeyValue("event.id", event.eventId()).addKeyValue("pnr.locator", event.locator())
                    .addKeyValue("failure", e.getClass().getSimpleName())
                    .log("E-ticket not attached; the confirmation is sent without it");
            return List.of();
        }
    }

    public void bookingCancelled(BookingCancelledEvent event) {
        deliver(event, EmailComposer.bookingCancelled(event));
    }

    public void bookingDisrupted(BookingDisruptedEvent event) {
        deliver(event, EmailComposer.bookingDisrupted(event));
    }

    public void passwordLink(AccountPasswordLinkEvent event) {
        // Opened only to build the link (ADR-0046): a token that does not open fails the event before anything is sent or recorded.
        String token = tokens.open(event.sealedToken(), event.type(), event.contactEmail());
        deliver(event, EmailComposer.passwordLink(event, token, siteUrl));
    }

    public void emailChanged(AccountEmailChangedEvent event) {
        // A notice made before the undo link existed has none; otherwise opened only to build the link (ADR-0046, ADR-0054).
        String token = event.canBeUndone() ? tokens.open(event.sealedUndoToken(), event.type(), event.contactEmail()) : null;
        deliver(event, EmailComposer.emailChanged(event, token, siteUrl));
    }

    public void emailChangeLink(AccountEmailChangeLinkEvent event) {
        String token = tokens.open(event.sealedToken(), event.type(), event.contactEmail());
        deliver(event, EmailComposer.emailChangeLink(event, token, siteUrl));
    }

    public void refundCompleted(BookingRefundCompletedEvent event) {
        deliver(event, EmailComposer.refundCompleted(event));
    }

    public void checkInOpen(BookingCheckInOpenEvent event) {
        deliver(event, EmailComposer.checkInOpen(event));
    }

    public void bookingDisruptionWithdrawn(BookingDisruptionWithdrawnEvent event) {
        deliver(event, EmailComposer.bookingDisruptionWithdrawn(event));
    }

    public void checkedIn(BookingCheckedInEvent event) {
        List<EmailMessage.Attachment> passes = passAttachments(event);
        deliver(event, EmailComposer.checkedIn(event, !passes.isEmpty()).withAttachments(passes));
    }

    /**
     * A QR picture of each passenger's pass (ADR-0074), or none at all: the confirmation is what matters, and the pass is on SkyHub either
     * way, so a pass that will not open (usually a key that differs between the services) or a picture that cannot be drawn sends the email
     * without any, counts it, and says so in the log. All or none, so the email never claims passes it does not carry. The pass text, the
     * picture and the address are never logged.
     */
    private List<EmailMessage.Attachment> passAttachments(BookingCheckedInEvent event) {
        if (event.passengers().stream().anyMatch(p -> p.sealedPass() == null)) {
            return List.of();   // from before passes were attached
        }
        try {
            List<EmailMessage.Attachment> attachments = new ArrayList<>();
            for (BookingCheckedInEvent.CheckedInPassenger passenger : event.passengers()) {
                String pass = tokens.open(passenger.sealedPass(), event.type(), event.contactEmail());
                attachments.add(new EmailMessage.Attachment("boarding-pass-" + event.flightNumber() + "-" + passenger.seat() + ".png",
                        "image/png", images.qrPng(pass)));
            }
            metrics.attachments("attached", attachments.size());
            return attachments;
        } catch (RuntimeException e) {
            metrics.attachments("dropped", 1);
            log.atWarn().addKeyValue("event.id", event.eventId()).addKeyValue("event.type", event.type())
                    .addKeyValue("pnr.locator", event.locator()).addKeyValue("failure", e.getClass().getSimpleName())
                    .log("Boarding passes not attached; the confirmation is sent without them");
            return List.of();
        }
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
