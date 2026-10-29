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

    @Column(nullable = false, length = 254)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PnrStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64, unique = true)
    private String idempotencyKey;

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

    public static Pnr confirmed(String locator, String contactEmail, List<PnrSegment> segments,
                                List<PnrPassenger> passengers, Instant createdAt, String idempotencyKey) {
        Pnr pnr = new Pnr();
        pnr.locator = locator;
        pnr.contactEmail = contactEmail;
        pnr.status = PnrStatus.CONFIRMED;
        pnr.segments = new ArrayList<>(segments);
        pnr.passengers = new ArrayList<>(passengers);
        pnr.createdAt = createdAt;
        pnr.idempotencyKey = idempotencyKey;
        return pnr;
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

    public String getContactEmail() {
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
