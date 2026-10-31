package com.skyhub.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A parked message the replay must leave out (ADR-0037): where it sits on the dead-letter topic, and why. */
@Entity
@Table(name = "skipped_dead_letter")
public class SkippedLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kafka_partition", nullable = false)
    private int partition;

    @Column(name = "kafka_offset", nullable = false)
    private long offset;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant skippedAt;

    protected SkippedLetter() {
    }

    public static SkippedLetter of(int partition, long offset, String reason, Instant skippedAt) {
        SkippedLetter letter = new SkippedLetter();
        letter.partition = partition;
        letter.offset = offset;
        letter.reason = reason;
        letter.skippedAt = skippedAt;
        return letter;
    }

    public int getPartition() {
        return partition;
    }

    public long getOffset() {
        return offset;
    }

    public String getReason() {
        return reason;
    }

    public Instant getSkippedAt() {
        return skippedAt;
    }
}
