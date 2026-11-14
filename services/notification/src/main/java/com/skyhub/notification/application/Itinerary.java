package com.skyhub.notification.application;

import com.skyhub.notification.domain.BookingConfirmedEvent;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A confirmed booking as a traveller reads it (ADR-0087): each flight with its route in local time at each airport, the airports'
 * names, and who sits where. The email and the PDF are both written from this, so they cannot disagree. Only an event that
 * carries routes, times and seats has one.
 */
public record Itinerary(String locator, List<String> passengers, List<Leg> legs, String total) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final String ARROW = "→";

    public Itinerary {
        passengers = List.copyOf(passengers);
        legs = List.copyOf(legs);
    }

    public record Seat(String name, String seat) {
    }

    /**
     * One flight. {@code departs} and {@code arrives} are local times at each airport ("07:30"); {@code arrives} is null when the
     * landing time is not known; {@code laterDays} is how many days after the local departure date it lands.
     */
    public record Leg(String flightNumber, String day, Airports.Airport from, String departs, Airports.Airport to, String arrives,
                      long laterDays, List<Seat> seats) {

        public Leg {
            seats = List.copyOf(seats);
        }

        /** "SK501 · Mon 2 Nov 2026" */
        public String heading() {
            return flightNumber + " · " + day;
        }

        /** "CMB 07:30 → DXB 10:50", with " (+1)" when it lands a day later, and no landing time when it is not known. */
        public String route() {
            return from.code() + " " + departs + " " + ARROW + " " + to.code() + (arrives == null ? "" : " " + arrives + landing());
        }

        /** "(+1)" for a flight that lands a day later, or nothing. */
        public String landing() {
            return laterDays > 0 ? " (+" + laterDays + ")" : "";
        }

        /** "Colombo, Bandaranaike International → Dubai, Dubai International" */
        public String airports() {
            return from.described() + " " + ARROW + " " + to.described();
        }
    }

    /** The itinerary of an event that carries routes, times and seats; empty for one from before it did (ADR-0087). */
    public static Optional<Itinerary> of(BookingConfirmedEvent event) {
        List<BookingConfirmedEvent.Segment> segments = event.segments();
        boolean described = !segments.isEmpty() && segments.stream().allMatch(s -> s.origin() != null && s.destination() != null
                && s.departure() != null);
        boolean seated = event.passengers().stream().allMatch(p -> p.seats().size() == segments.size());
        if (!described || !seated || event.passengers().isEmpty()) {
            return Optional.empty();
        }
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            int leg = i;
            List<Seat> seats = event.passengers().stream()
                    .map(p -> new Seat(name(p), p.seats().get(leg))).toList();
            legs.add(leg(segments.get(i), seats));
        }
        List<String> names = event.passengers().stream().map(Itinerary::name).toList();
        return Optional.of(new Itinerary(event.locator(), names, legs, event.totalAmount().toPlainString() + " " + event.currency()));
    }

    /**
     * One flight as a traveller reads it, for an email about a single flight such as a move (ADR-0088); empty when the segment does
     * not carry its route and departure (an event from before they were sent).
     */
    public static Optional<Leg> of(BookingConfirmedEvent.Segment segment, List<Seat> seats) {
        if (segment == null || segment.origin() == null || segment.destination() == null || segment.departure() == null) {
            return Optional.empty();
        }
        return Optional.of(leg(segment, seats));
    }

    private static Leg leg(BookingConfirmedEvent.Segment segment, List<Seat> seats) {
        Airports.Airport from = Airports.of(segment.origin());
        Airports.Airport to = Airports.of(segment.destination());
        ZonedDateTime leaves = segment.departure().atZone(from.zone());
        Instant arrival = segment.arrival();
        ZonedDateTime lands = arrival == null ? null : arrival.atZone(to.zone());
        long laterDays = lands == null ? 0 : ChronoUnit.DAYS.between(leaves.toLocalDate(), lands.toLocalDate());
        return new Leg(segment.flightNumber(), DAY.format(leaves), from, TIME.format(leaves), to,
                lands == null ? null : TIME.format(lands), laterDays, seats);
    }

    private static String name(BookingConfirmedEvent.Passenger passenger) {
        return passenger.firstName() + " " + passenger.lastName();
    }
}
