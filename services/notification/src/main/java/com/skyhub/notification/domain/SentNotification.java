package com.skyhub.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that an event was handled (ADR-0019). The unique {@code eventId} makes the consumer idempotent. Holds the
 * recipient only in masked form, so this table is not a list of travellers' addresses.
 */
@Entity
@Table(name = "sent_notification")
public class SentNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 60)
    private String type;

    /** Null for an event that belongs to no booking (an account link). */
    @Column(length = 40)
    private String locator;

    @Column(nullable = false, length = 254)
    private String recipient;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false)
    private Instant sentAt;

    protected SentNotification() {
    }

    public static SentNotification of(UUID eventId, String type, String locator, String maskedRecipient, String subject,
                                      Instant sentAt) {
        SentNotification row = new SentNotification();
        row.eventId = eventId;
        row.type = type;
        row.locator = locator;
        row.recipient = maskedRecipient;
        row.subject = subject;
        row.sentAt = sentAt;
        return row;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getLocator() {
        return locator;
    }

    public String getRecipient() {
        return recipient;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
