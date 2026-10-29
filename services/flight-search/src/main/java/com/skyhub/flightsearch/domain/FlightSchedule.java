package com.skyhub.flightsearch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** A timetable entry. Dated flights are derived from it with {@link #legOn(LocalDate)} (ADR-0004). */
@Entity
@Table(name = "flight_schedule")
public class FlightSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8)
    private String flightNumber;

    @Column(nullable = false, length = 3)
    private String origin;

    @Column(nullable = false, length = 3)
    private String destination;

    @Column(name = "departure_time_utc", nullable = false)
    private LocalTime departureTimeUtc;

    @Column(nullable = false)
    private int durationMinutes;

    @Column(nullable = false, length = 10)
    private String aircraftType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fareFrom;

    @Column(nullable = false, length = 3)
    private String currency;

    /** ISO day numbers the flight operates, e.g. "1234567" daily or "135" Mon/Wed/Fri. */
    @Column(nullable = false, length = 7)
    private String operatingDays;

    protected FlightSchedule() {
    }

    public FlightSchedule(String flightNumber, String origin, String destination, LocalTime departureTimeUtc,
                          int durationMinutes, String aircraftType, BigDecimal fareFrom, String currency,
                          String operatingDays) {
        this.flightNumber = flightNumber;
        this.origin = origin;
        this.destination = destination;
        this.departureTimeUtc = departureTimeUtc;
        this.durationMinutes = durationMinutes;
        this.aircraftType = aircraftType;
        this.fareFrom = fareFrom;
        this.currency = currency;
        this.operatingDays = operatingDays;
    }

    public boolean operatesOn(LocalDate date) {
        return operatingDays.indexOf(Character.forDigit(date.getDayOfWeek().getValue(), 10)) >= 0;
    }

    public FlightLeg legOn(LocalDate date) {
        OffsetDateTime departure = OffsetDateTime.of(date, departureTimeUtc, ZoneOffset.UTC);
        return new FlightLeg(flightNumber, origin, destination, departure, departure.plusMinutes(durationMinutes),
                aircraftType, fareFrom, currency);
    }

    public String getFlightNumber() {
        return flightNumber;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }
}
