package com.skyhub.booking.application.rebooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.application.rebooking.RebookingContext.Neighbour;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Trip: SK501 DXB-LHR (the flight being moved, leg 1 of 3), then SK700 LHR-JFK. The replacement is SK502, a day later in the
 * tests that move it. All times are UTC.
 */
class MustKeepTheConnectionsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final PnrSegment FROM = new PnrSegment("SK501", DAY);
    private static final PnrSegment TO = new PnrSegment("SK502", DAY);
    private static final PnrSegment BEFORE = new PnrSegment("SK400", DAY);
    private static final PnrSegment AFTER = new PnrSegment("SK700", DAY);
    private static final Pnr PNR = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(BEFORE, FROM, AFTER),
            List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "12D", "13D"))), NOW, "key", "fingerprint");

    private final MustKeepTheConnections rule = new MustKeepTheConnections();

    private static Instant at(String time) {
        return Instant.parse("2026-11-02T" + time + ":00Z");
    }

    private static FlightInfo flight(String departs, String arrives) {
        return new FlightInfo(at(departs), Money.of("100.00", "USD"), "DXB", "LHR", arrives == null ? null : at(arrives));
    }

    private static RebookingContext moving(FlightInfo replacement, Optional<Disruption> replacementDisruption,
                                           Optional<Neighbour> previous, Optional<Neighbour> next) {
        return new RebookingContext(PNR, 1, FROM, TO, flight("10:00", "14:00"), replacement, Optional.empty(), replacementDisruption,
                false, previous, next);
    }

    private static Optional<Neighbour> next(String departs) {
        return Optional.of(new Neighbour(AFTER, flight(departs, "23:00"), Optional.empty()));
    }

    private static Optional<Neighbour> previous(String arrives) {
        return Optional.of(new Neighbour(BEFORE, flight("01:00", arrives), Optional.empty()));
    }

    @Test
    void should_have_nothing_to_judge_for_a_booking_with_a_single_flight() {
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), Optional.empty(), Optional.empty()))).isEmpty();
    }

    @Test
    void should_allow_a_replacement_that_lands_at_least_an_hour_before_the_next_flight() {
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), Optional.empty(), next("17:00")))).isEmpty();
    }

    @Test
    void should_refuse_a_replacement_that_lands_less_than_an_hour_before_the_next_flight_and_say_by_how_much() {
        var refusal = rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), Optional.empty(), next("16:20")));

        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason)
                .contains("SK502").contains("16:00").contains("SK700").contains("16:20").contains("20 minutes").contains("60"));
    }

    @Test
    void should_refuse_a_replacement_that_lands_after_the_next_flight_has_gone() {
        assertThat(rule.refusal(moving(flight("12:00", "18:00"), Optional.empty(), Optional.empty(), next("16:40")))).isPresent();
    }

    @Test
    void should_allow_a_replacement_that_leaves_at_least_an_hour_after_the_previous_flight_lands() {
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), previous("11:00"), Optional.empty()))).isEmpty();
    }

    @Test
    void should_refuse_a_replacement_that_leaves_before_the_previous_flight_has_landed_enough() {
        var refusal = rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), previous("11:30"), Optional.empty()));

        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason)
                .contains("SK400").contains("11:30").contains("SK502").contains("12:00").contains("30 minutes"));
    }

    @Test
    void should_judge_the_replacement_on_its_expected_times_when_it_is_delayed() {
        // Leaves 12:00, lands 16:00 on schedule; delayed 2h, it lands 18:00, which misses a 17:00 onward flight.
        var delayed = Optional.of(Disruption.delayed("SK502", DAY, at("14:00"), "Late aircraft", NOW));

        assertThat(rule.refusal(moving(flight("12:00", "16:00"), delayed, Optional.empty(), next("17:00")))).isPresent();
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), delayed, Optional.empty(), next("19:30")))).isEmpty();
    }

    @Test
    void should_judge_a_neighbour_on_its_expected_times_when_it_is_delayed() {
        // The onward flight was due 17:00 but is delayed 2h to 19:00, so a replacement landing 16:40 now has time.
        var delayedNext = Optional.of(new Neighbour(AFTER, flight("17:00", "23:00"),
                Optional.of(Disruption.delayed("SK700", DAY, at("19:00"), "Late aircraft", NOW))));
        // The previous flight was due in at 11:00 but is delayed 1h30 and lands 12:30: too late for a 12:00 replacement.
        var delayedPrevious = Optional.of(new Neighbour(BEFORE, flight("01:00", "11:00"),
                Optional.of(Disruption.delayed("SK400", DAY, at("02:30"), "Late aircraft", NOW))));

        assertThat(rule.refusal(moving(flight("12:00", "16:40"), Optional.empty(), Optional.empty(), delayedNext))).isEmpty();
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), delayedPrevious, Optional.empty()))).isPresent();
    }

    @Test
    void should_not_hold_a_cancelled_neighbour_against_the_move() {
        var cancelledNext = Optional.of(new Neighbour(AFTER, flight("16:10", "23:00"),
                Optional.of(Disruption.cancelled("SK700", DAY, "Weather", NOW))));
        var cancelledPrevious = Optional.of(new Neighbour(BEFORE, flight("01:00", "11:50"),
                Optional.of(Disruption.cancelled("SK400", DAY, "Weather", NOW))));

        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), cancelledPrevious, cancelledNext))).isEmpty();
    }

    @Test
    void should_refuse_rather_than_guess_when_the_replacements_arrival_is_not_known() {
        var refusal = rule.refusal(moving(flight("12:00", null), Optional.empty(), Optional.empty(), next("20:00")));

        assertThat(refusal).hasValueSatisfying(reason -> assertThat(reason).contains("SK502").contains("arrival"));
    }

    @Test
    void should_refuse_rather_than_guess_when_a_neighbours_arrival_is_not_known_for_the_previous_join() {
        var unknown = Optional.of(new Neighbour(BEFORE, flight("01:00", null), Optional.empty()));

        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), unknown, Optional.empty())))
                .hasValueSatisfying(reason -> assertThat(reason).contains("SK400").contains("arrival"));
    }

    @Test
    void should_allow_exactly_the_minimum_connection() {
        assertThat(rule.refusal(moving(flight("12:00", "16:00"), Optional.empty(), Optional.empty(), next("17:00")))).isEmpty();
        assertThat(MustKeepTheConnections.MINIMUM).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void should_be_one_of_the_rules_every_rebooking_must_satisfy() {
        assertThat(RebookingRules.defaults()).anyMatch(r -> r instanceof MustKeepTheConnections);
    }
}
