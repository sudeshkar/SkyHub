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

    public String getPnrLocator() {
        return pnrLocator;
    }
}
