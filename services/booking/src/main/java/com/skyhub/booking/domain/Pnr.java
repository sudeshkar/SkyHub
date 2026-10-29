package com.skyhub.booking.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Passenger name record: the aggregate root of a booking. */
@Entity
@Table(name = "pnr")
public class Pnr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6, unique = true)
    private String locator;

    /** Encrypted at rest by an auto-applied converter in the persistence layer. */
    @Column(name = "contact_email", nullable = false, length = 512)
    private ContactEmail contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PnrStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** SHA-256 of the client's Idempotency-Key; the raw key is never stored. */
    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKeyHash;

    /** SHA-256 of the request that created this PNR; a replayed key must carry the same request. */
    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    /** Optimistic lock: two concurrent changes to one booking (cancel and change seat) cannot both win. */
    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pnr_segment", joinColumns = @JoinColumn(name = "pnr_id"))
    @OrderColumn(name = "segment_order")
    private List<PnrSegment> segments = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "pnr_id", nullable = false)
    @OrderColumn(name = "passenger_order")
    private List<PnrPassenger> passengers = new ArrayList<>();

    protected Pnr() {
    }

    public static Pnr confirmed(String locator, ContactEmail contactEmail, List<PnrSegment> segments,
                                List<PnrPassenger> passengers, Instant createdAt, String idempotencyKeyHash,
                                String requestFingerprint) {
        Pnr pnr = new Pnr();
        pnr.locator = locator;
        pnr.contactEmail = contactEmail;
        pnr.status = PnrStatus.CONFIRMED;
        pnr.segments = new ArrayList<>(segments);
        pnr.passengers = new ArrayList<>(passengers);
        pnr.createdAt = createdAt;
        pnr.idempotencyKeyHash = idempotencyKeyHash;
        pnr.requestFingerprint = requestFingerprint;
        return pnr;
    }

    public boolean wasCreatedBy(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    /** @return true if the booking was cancelled by this call, false if it already was (idempotent) */
    public boolean cancel() {
        if (status == PnrStatus.CANCELLED) {
            return false;
        }
        status = PnrStatus.CANCELLED;
        return true;
    }

    /** The seat a passenger holds on a flight of this booking. */
    public String seatOf(int passengerIndex, int segmentIndex) {
        requirePosition(passengerIndex, segmentIndex);
        return passengers.get(passengerIndex).getSeats().get(segmentIndex);
    }

    /**
     * Moves a passenger to another seat on one flight. Seat inventory is updated by the caller in the
     * same transaction; this keeps the booking itself consistent.
     *
     * @return the seat the passenger left
     */
    public String changeSeat(int passengerIndex, int segmentIndex, String newSeat) {
        requireActive();
        requirePosition(passengerIndex, segmentIndex);
        for (int p = 0; p < passengers.size(); p++) {
            if (p != passengerIndex && passengers.get(p).getSeats().get(segmentIndex).equals(newSeat)) {
                throw new InvalidBookingException("Seat " + newSeat + " is already assigned to another passenger on this flight");
            }
        }
        return passengers.get(passengerIndex).replaceSeat(segmentIndex, newSeat);
    }

    /** Lets callers check before touching seat inventory, so a refused change never books anything. */
    public void requireActive() {
        if (status == PnrStatus.CANCELLED) {
            throw new BookingCancelledException("Booking " + locator + " is cancelled");
        }
    }

    private void requirePosition(int passengerIndex, int segmentIndex) {
        if (passengerIndex < 0 || passengerIndex >= passengers.size()) {
            throw new InvalidBookingException("This booking has no passenger " + passengerIndex);
        }
        if (segmentIndex < 0 || segmentIndex >= segments.size()) {
            throw new InvalidBookingException("This booking has no flight " + segmentIndex);
        }
    }

    /** Retrieval check for "manage booking": any passenger's last name, ignoring case and surrounding spaces. */
    public boolean hasPassengerWithLastName(String lastName) {
        String wanted = normalise(lastName);
        return passengers.stream().anyMatch(p -> normalise(p.getLastName()).equals(wanted));
    }

    private static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public String getLocator() {
        return locator;
    }

    public ContactEmail getContactEmail() {
        return contactEmail;
    }

    public PnrStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PnrSegment> getSegments() {
        return List.copyOf(segments);
    }

    public List<PnrPassenger> getPassengers() {
        return List.copyOf(passengers);
    }
}
