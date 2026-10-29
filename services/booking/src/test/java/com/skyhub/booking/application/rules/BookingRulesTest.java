package com.skyhub.booking.application.rules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrSegment;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingRulesTest {

    private static final PnrSegment SK501 = new PnrSegment("SK501", LocalDate.of(2026, 11, 2));
    private static final PnrSegment SK901 = new PnrSegment("SK901", LocalDate.of(2026, 11, 2));
    private static final PassengerCommand PRIYA = new PassengerCommand("Priya", "Perera", List.of("11D", "24A"));
    private static final PassengerCommand RAJ = new PassengerCommand("Raj", "Perera", List.of("11E", "24B"));

    private static CreatePnrCommand booking(List<PnrSegment> segments, List<PassengerCommand> passengers) {
        return new CreatePnrCommand("priya@example.com", segments, passengers);
    }

    @Test
    void every_rule_accepts_a_valid_booking() {
        var valid = booking(List.of(SK501, SK901), List.of(PRIYA, RAJ));
        List<BookingRule> rules = List.of(new SegmentCountRule(), new PassengerCountRule(), new DistinctFlightsRule(),
                new OneSeatPerFlightRule(), new NoSharedSeatsRule());

        rules.forEach(rule -> assertThatCode(() -> rule.check(valid)).doesNotThrowAnyException());
    }

    @Test
    void segment_count_rule_rejects_no_flights_and_more_than_four() {
        var five = Collections.nCopies(5, SK501);

        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new SegmentCountRule().check(booking(List.of(), List.of(PRIYA))));
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new SegmentCountRule().check(booking(five, List.of(PRIYA))));
    }

    @Test
    void passenger_count_rule_rejects_more_than_nine() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new PassengerCountRule().check(booking(List.of(SK501), Collections.nCopies(10, PRIYA))));
    }

    @Test
    void distinct_flights_rule_rejects_a_repeated_flight() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new DistinctFlightsRule().check(booking(List.of(SK501, SK501), List.of(PRIYA))));
    }

    @Test
    void one_seat_per_flight_rule_rejects_a_missing_seat() {
        var oneSeat = new PassengerCommand("Priya", "Perera", List.of("11D"));

        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new OneSeatPerFlightRule().check(booking(List.of(SK501, SK901), List.of(oneSeat))))
                .withMessageContaining("one seat per flight");
    }

    @Test
    void no_shared_seats_rule_names_the_seat_and_flight() {
        var sameSeat = new PassengerCommand("Raj", "Perera", List.of("11D", "24B"));

        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new NoSharedSeatsRule().check(booking(List.of(SK501, SK901), List.of(PRIYA, sameSeat))))
                .withMessage("Seat 11D is selected twice on SK501");
    }
}
