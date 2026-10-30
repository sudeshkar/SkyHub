package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The audit record of one move (ADR-0023): who moved which booking, off which flight, onto which, and when. Written in the
 * same transaction as the move, never changed afterwards. It holds no names or contact details.
 */
@Entity
@Table(name = "rebooking")
public class Rebooking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String locator;

    @Column(nullable = false)
    private int segmentIndex;

    @Column(nullable = false, length = 8)
    private String fromFlight;

    @Column(nullable = false)
    private LocalDate fromDate;

    @Column(nullable = false, length = 8)
    private String toFlight;

    @Column(nullable = false)
    private LocalDate toDate;

    @Column(nullable = false, length = 40)
    private String rebookedBy;

    @Column(nullable = false)
    private Instant rebookedAt;

    protected Rebooking() {
    }

    public static Rebooking of(String locator, int segmentIndex, PnrSegment from, PnrSegment to, String rebookedBy, Instant at) {
        Rebooking row = new Rebooking();
        row.locator = locator;
        row.segmentIndex = segmentIndex;
        row.fromFlight = from.flightNumber();
        row.fromDate = from.departureDate();
        row.toFlight = to.flightNumber();
        row.toDate = to.departureDate();
        row.rebookedBy = rebookedBy;
        row.rebookedAt = at;
        return row;
    }

    public String getLocator() {
        return locator;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public String getFromFlight() {
        return fromFlight;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public String getRebookedBy() {
        return rebookedBy;
    }

    public Instant getRebookedAt() {
        return rebookedAt;
    }
}
