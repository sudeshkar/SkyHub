package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.notification.domain.BookingConfirmedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItineraryTest {

    /** Colombo to Dubai, 02:00 to 06:50 UTC: 07:30 in Colombo (UTC+5:30), 10:50 in Dubai (UTC+4). */
    static final BookingConfirmedEvent.Segment SK501 = new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2),
            "CMB", "DXB", Instant.parse("2026-11-02T02:00:00Z"), Instant.parse("2026-11-02T06:50:00Z"));
    /** London to Dubai, leaving 14:00 in London (GMT in November) and landing 00:50 the next day in Dubai. */
    static final BookingConfirmedEvent.Segment SK002 = new BookingConfirmedEvent.Segment("SK002", LocalDate.of(2026, 11, 20),
            "LHR", "DXB", Instant.parse("2026-11-20T14:00:00Z"), Instant.parse("2026-11-20T20:50:00Z"));

    static BookingConfirmedEvent confirmed(List<BookingConfirmedEvent.Segment> segments, List<BookingConfirmedEvent.Passenger> passengers) {
        return new BookingConfirmedEvent(UUID.randomUUID(), "booking.confirmed", 1, Instant.parse("2026-10-30T10:00:00Z"), "ABC234",
                "priya@example.com", passengers, segments, new BigDecimal("1240.00"), "USD");
    }

    @Test
    void should_show_each_flight_in_local_time_at_each_airport_with_the_names_of_the_airports() {
        Itinerary trip = Itinerary.of(confirmed(List.of(SK501),
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera", List.of("11D"))))).orElseThrow();

        Itinerary.Leg leg = trip.legs().getFirst();
        assertThat(leg.heading()).isEqualTo("SK501 · Mon 2 Nov 2026");
        assertThat(leg.route()).isEqualTo("CMB 07:30 → DXB 10:50");
        assertThat(leg.airports()).isEqualTo("Colombo, Bandaranaike International → Dubai, Dubai International");
        assertThat(leg.seats()).containsExactly(new Itinerary.Seat("Priya Perera", "11D"));
    }

    @Test
    void should_mark_a_flight_that_lands_on_a_later_day_where_it_lands() {
        Itinerary trip = Itinerary.of(confirmed(List.of(SK002),
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera", List.of("24A"))))).orElseThrow();

        assertThat(trip.legs().getFirst().heading()).isEqualTo("SK002 · Fri 20 Nov 2026");
        assertThat(trip.legs().getFirst().route()).isEqualTo("LHR 14:00 → DXB 00:50 (+1)");
    }

    @Test
    void should_give_each_passenger_the_seat_they_hold_on_each_flight() {
        Itinerary trip = Itinerary.of(confirmed(List.of(SK501, SK002), List.of(
                new BookingConfirmedEvent.Passenger("Priya", "Perera", List.of("11D", "24A")),
                new BookingConfirmedEvent.Passenger("Anil", "Perera", List.of("11E", "24B"))))).orElseThrow();

        assertThat(trip.legs().get(0).seats()).extracting(Itinerary.Seat::seat).containsExactly("11D", "11E");
        assertThat(trip.legs().get(1).seats()).extracting(Itinerary.Seat::seat).containsExactly("24A", "24B");
        assertThat(trip.passengers()).containsExactly("Priya Perera", "Anil Perera");
        assertThat(trip.total()).isEqualTo("1240.00 USD");
        assertThat(trip.locator()).isEqualTo("ABC234");
    }

    @Test
    void should_leave_the_landing_time_out_when_it_is_not_known_rather_than_guess() {
        var noArrival = new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2), "CMB", "DXB",
                Instant.parse("2026-11-02T02:00:00Z"), null);

        Itinerary trip = Itinerary.of(confirmed(List.of(noArrival),
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera", List.of("11D"))))).orElseThrow();

        assertThat(trip.legs().getFirst().route()).isEqualTo("CMB 07:30 → DXB");
    }

    @Test
    void should_show_an_airport_it_does_not_know_by_its_code_in_utc() {
        var unknown = new BookingConfirmedEvent.Segment("SK777", LocalDate.of(2026, 11, 2), "XYZ", "DXB",
                Instant.parse("2026-11-02T02:00:00Z"), Instant.parse("2026-11-02T06:50:00Z"));

        Itinerary.Leg leg = Itinerary.of(confirmed(List.of(unknown),
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera", List.of("11D"))))).orElseThrow().legs().getFirst();

        assertThat(leg.route()).isEqualTo("XYZ 02:00 → DXB 10:50");
        assertThat(leg.airports()).startsWith("XYZ → ");
    }

    @Test
    void should_have_no_itinerary_for_an_event_from_before_it_carried_routes_times_and_seats() {
        assertThat(Itinerary.of(confirmed(List.of(new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2))),
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera"))))).isEmpty();
        // routes and times, but no seats: still not enough to say who sits where
        assertThat(Itinerary.of(confirmed(List.of(SK501), List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera"))))).isEmpty();
    }
}
