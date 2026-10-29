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

/** One seat on one flight on one date. See docs/adr/0002-optimistic-locking-for-seats.md. */
@Entity
@Table(name = "seat_inventory")
public class SeatInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String flightNumber;

    @Column(nullable = false)
    private LocalDate departureDate;

    @Column(nullable = false, length = 4)
    private String seatNumber;

    @Column(nullable = false)
    private int rowNumber;

    @Column(nullable = false, length = 1)
    private String letter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Cabin cabin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatStatus status;

    @Column(length = 6)
    private String pnrLocator;

    /** Set while the seat is HELD: who holds it (an unguessable id) and until when (ADR-0012). */
    @Column(length = 36)
    private String holdId;

    private Instant heldUntil;

    @Version
    private long version;

    protected SeatInventory() {
    }

    public static SeatInventory available(String flightNumber, LocalDate departureDate, SeatSpec spec) {
        SeatInventory seat = new SeatInventory();
        seat.flightNumber = flightNumber;
        seat.departureDate = departureDate;
        seat.seatNumber = spec.seatNumber();
        seat.rowNumber = spec.row();
        seat.letter = spec.letter();
        seat.cabin = spec.cabin();
        seat.status = SeatStatus.AVAILABLE;
        return seat;
    }

    public void book(String locator) {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException(
                    "Seat " + seatNumber + " on " + flightNumber + " " + departureDate + " is no longer available");
        }
        status = SeatStatus.BOOKED;
        pnrLocator = locator;
    }

    /** Holds an available seat for whoever knows {@code holdId}, until {@code until}. */
    public void hold(String holdId, Instant until) {
        requireAvailable();
        status = SeatStatus.HELD;
        this.holdId = holdId;
        this.heldUntil = until;
    }

    /** Lets a caller check a whole group of seats before changing any of them. */
    public void requireAvailable() {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException(
                    "Seat " + seatNumber + " on " + flightNumber + " " + departureDate + " is no longer available");
        }
    }

    /** Lets go of a hold. Only its owner can; anything else (already released, booked, someone else's) is ignored. */
    public void releaseHold(String holdId) {
        if (status == SeatStatus.HELD && holdId.equals(this.holdId)) {
            clearHold();
        }
    }

    /**
     * Turns a hold into a booking. The expiry time is checked here, not left to the cleanup job, so an
     * expired hold can never be used however late the cleanup runs.
     */
    public void bookFromHold(String holdId, String locator, Instant now) {
        if (status != SeatStatus.HELD || !holdId.equals(this.holdId)) {
            throw new SeatUnavailableException(
                    "Seat " + seatNumber + " on " + flightNumber + " " + departureDate + " is not held for this booking");
        }
        if (!now.isBefore(heldUntil)) {
            throw new SeatUnavailableException("Your hold on seat " + seatNumber + " on " + flightNumber + " has expired");
        }
        clearHold();
        status = SeatStatus.BOOKED;
        pnrLocator = locator;
    }

    private void clearHold() {
        status = SeatStatus.AVAILABLE;
        holdId = null;
        heldUntil = null;
    }

    /**
     * Frees the seat. Only the booking that holds it may release it; anything else means the data is
     * inconsistent, which must fail loudly rather than free someone else's seat.
     */
    public void release(String locator) {
        if (status != SeatStatus.BOOKED || !locator.equals(pnrLocator)) {
            throw new IllegalStateException(
                    "Seat " + seatNumber + " on " + flightNumber + " " + departureDate + " is not held by booking " + locator);
        }
        status = SeatStatus.AVAILABLE;
        pnrLocator = null;
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public LocalDate getDepartureDate() {
        return departureDate;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getLetter() {
        return letter;
    }

    public Cabin getCabin() {
        return cabin;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getHoldId() {
        return holdId;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }

    public String getPnrLocator() {
        return pnrLocator;
    }
}
