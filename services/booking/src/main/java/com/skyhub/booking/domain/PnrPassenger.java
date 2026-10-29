package com.skyhub.booking.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pnr_passenger")
public class PnrPassenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String firstName;

    @Column(nullable = false, length = 50)
    private String lastName;

    /** One seat per segment, in segment order. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pnr_passenger_seat", joinColumns = @JoinColumn(name = "passenger_id"))
    @OrderColumn(name = "segment_order")
    @Column(name = "seat_number", nullable = false, length = 4)
    private List<String> seats = new ArrayList<>();

    protected PnrPassenger() {
    }

    public PnrPassenger(String firstName, String lastName, List<String> seats) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.seats = new ArrayList<>(seats);
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public List<String> getSeats() {
        return List.copyOf(seats);
    }
}
