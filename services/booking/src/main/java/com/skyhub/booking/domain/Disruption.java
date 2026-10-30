package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A delay or cancellation of one flight on one date (ADR-0014). The timetable says what is scheduled; this is
 * the exception laid over it. There is at most one per flight and date: recording again replaces it.
 */
@Entity
@Table(name = "disruption")
public class Disruption {

    public static final int MAX_REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String flightNumber;

    @Column(nullable = false)
    private LocalDate departureDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DisruptionType type;

    private Instant revisedDeparture;

    @Column(nullable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(nullable = false)
    private Instant recordedAt;

    /** The staff username who recorded it (ADR-0017). Null on rows from before staff accounts existed. */
    @Column(length = 40)
    private String recordedBy;

    /** Two operators recording the same flight at once must not silently overwrite each other. */
    @Version
    private long version;

    protected Disruption() {
    }

    public static Disruption delayed(String flightNumber, LocalDate departureDate, Instant revisedDeparture,
                                     String reason, Instant recordedAt) {
        Disruption disruption = new Disruption();
        disruption.flightNumber = flightNumber;
        disruption.departureDate = departureDate;
        disruption.becomeDelayed(revisedDeparture, reason, recordedAt);
        return disruption;
    }

    public static Disruption cancelled(String flightNumber, LocalDate departureDate, String reason, Instant recordedAt) {
        Disruption disruption = new Disruption();
        disruption.flightNumber = flightNumber;
        disruption.departureDate = departureDate;
        disruption.becomeCancelled(reason, recordedAt);
        return disruption;
    }

    public void becomeDelayed(Instant revisedDeparture, String reason, Instant recordedAt) {
        if (revisedDeparture == null) {
            throw new InvalidBookingException("A delay needs a revised departure time");
        }
        String text = cleaned(reason); // validate everything before changing anything
        this.type = DisruptionType.DELAYED;
        this.revisedDeparture = revisedDeparture;
        this.reason = text;
        this.recordedAt = recordedAt;
    }

    public void becomeCancelled(String reason, Instant recordedAt) {
        String text = cleaned(reason);
        this.type = DisruptionType.CANCELLED;
        this.revisedDeparture = null;
        this.reason = text;
        this.recordedAt = recordedAt;
    }

    public void recordedBy(String username) {
        this.recordedBy = username;
    }

    public boolean isCancelled() {
        return type == DisruptionType.CANCELLED;
    }

    private static String cleaned(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() > MAX_REASON_LENGTH) {
            throw new InvalidBookingException("The reason can be at most " + MAX_REASON_LENGTH + " characters");
        }
        return text;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public LocalDate getDepartureDate() {
        return departureDate;
    }

    public DisruptionType getType() {
        return type;
    }

    public Instant getRevisedDeparture() {
        return revisedDeparture;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getRecordedBy() {
        return recordedBy;
    }
}
