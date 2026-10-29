package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One passenger checked in on one flight of a booking (ADR-0013). Kept apart from the booking so check-in
 * never touches the PNR's version. The database allows one row per passenger per flight.
 */
@Entity
@Table(name = "check_in")
public class CheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String locator;

    @Column(nullable = false)
    private int segmentIndex;

    @Column(nullable = false)
    private int passengerIndex;

    @Column(nullable = false)
    private int boardingSequence;

    @Column(nullable = false)
    private Instant checkedInAt;

    protected CheckIn() {
    }

    public static CheckIn of(String locator, int segmentIndex, int passengerIndex, int boardingSequence, Instant checkedInAt) {
        CheckIn checkIn = new CheckIn();
        checkIn.locator = locator;
        checkIn.segmentIndex = segmentIndex;
        checkIn.passengerIndex = passengerIndex;
        checkIn.boardingSequence = boardingSequence;
        checkIn.checkedInAt = checkedInAt;
        return checkIn;
    }

    public String getLocator() {
        return locator;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public int getPassengerIndex() {
        return passengerIndex;
    }

    public int getBoardingSequence() {
        return boardingSequence;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }
}
