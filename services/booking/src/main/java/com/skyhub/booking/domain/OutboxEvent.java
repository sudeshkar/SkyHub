package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An event waiting to be published (ADR-0019). Written in the same transaction as the change it describes, so the
 * two happen together or not at all. {@code payload} is already encrypted: it holds the traveller's personal data.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;

    /** Position in the table; the relay publishes in this order. Set by the database. */
    @Column(insertable = false, updatable = false)
    private Long seq;

    /** What the event is about (the booking reference). Becomes the Kafka key, so one booking's events stay in order. */
    @Column(nullable = false, length = 40)
    private String aggregateId;

    @Column(nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {
    }

    /** The topic booking events have always gone to. */
    public static final String BOOKING_TOPIC = "skyhub.booking.events";

    public static OutboxEvent pending(UUID id, String aggregateId, String eventType, String encryptedPayload,
                                      Instant createdAt) {
        return pending(id, aggregateId, eventType, encryptedPayload, createdAt, BOOKING_TOPIC);
    }

    public static OutboxEvent pending(UUID id, String aggregateId, String eventType, String encryptedPayload,
                                      Instant createdAt, String topic) {
        OutboxEvent event = new OutboxEvent();
        event.topic = topic;
        event.id = id;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = encryptedPayload;
        event.createdAt = createdAt;
        return event;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The payload is personal data even encrypted; keep it out of logs. */
    @Override
    public String toString() {
        return "OutboxEvent[id=" + id + ", type=" + eventType + ", published=" + isPublished() + "]";
    }
}
