package com.skyhub.booking.application.rebooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RebookingRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant DEPARTS = Instant.parse("2026-11-02T08:30:00Z");
    private static final PnrSegment FROM = new PnrSegment("SK501", DAY);
    private static final PnrSegment TO = new PnrSegment("SK501", DAY.plusDays(1));

    private static FlightInfo flight(Instant departure, String origin, String destination) {
        return new FlightInfo(departure, Money.of("100.00", "USD"), origin, destination);
    }

    private static final Pnr PNR = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(FROM),
            List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, "key", "fingerprint");

    /** A context in which every rule is satisfied; each test spoils one thing. */
    private static RebookingContext fine() {
        return new RebookingContext(PNR, 0, FROM, TO, flight(DEPARTS, "DXB", "LHR"),
                flight(DEPARTS.plusSeconds(86_400), "DXB", "LHR"),
                Optional.of(Disruption.delayed("SK501", DAY, DEPARTS.plusSeconds(7200), "Late aircraft", NOW)), Optional.empty(), false);
    }

    private static RebookingContext with(java.util.function.UnaryOperator<RebookingContext> change) {
        return change.apply(fine());
    }

    @Test
    void should_let_a_sound_rebooking_through_every_rule() {
        for (RebookingRule rule : RebookingRules.defaults()) {
            assertThat(rule.refusal(fine())).as(rule.getClass().getSimpleName()).isEmpty();
        }
    }

    @Test
    void should_only_move_bookings_off_a_flight_with_a_recorded_disruption() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(), c.toInfo(),
                Optional.empty(), c.toDisruption(), false));

        assertThat(new FlightMustBeDisrupted().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("SK501").contains("no recorded"));
    }

    @Test
    void should_not_move_a_booking_onto_a_flight_that_is_itself_cancelled() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(), c.toInfo(), c.fromDisruption(),
                Optional.of(Disruption.cancelled("SK501", DAY.plusDays(1), "Weather", NOW)), false));

        assertThat(new TargetMustNotBeCancelled().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("cancelled"));
    }

    @Test
    void should_allow_a_delayed_target_flight() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(), c.toInfo(), c.fromDisruption(),
                Optional.of(Disruption.delayed("SK501", DAY.plusDays(1), DEPARTS.plusSeconds(90_000), "x", NOW)), false));

        assertThat(new TargetMustNotBeCancelled().refusal(ctx)).isEmpty();
    }

    @Test
    void should_keep_the_traveller_on_the_same_route() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(),
                flight(DEPARTS.plusSeconds(86_400), "DXB", "JFK"), c.fromDisruption(), c.toDisruption(), false));

        assertThat(new MustStayOnTheSameRoute().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("DXB to JFK").contains("DXB to LHR"));
    }

    @Test
    void should_refuse_when_a_route_cannot_be_checked_rather_than_assume_it_matches() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(),
                new FlightInfo(DEPARTS.plusSeconds(86_400), Money.of("100.00", "USD")), c.fromDisruption(), c.toDisruption(), false));

        assertThat(new MustStayOnTheSameRoute().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("route"));
    }

    @Test
    void should_not_move_a_traveller_to_a_flight_that_leaves_before_the_original_was_due() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(),
                flight(DEPARTS.minusSeconds(3600), "DXB", "LHR"), c.fromDisruption(), c.toDisruption(), false));

        assertThat(new MustNotLeaveEarlier().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("before"));
    }

    @Test
    void should_not_move_a_booking_that_is_already_checked_in_on_that_flight() {
        RebookingContext ctx = with(c -> new RebookingContext(c.pnr(), 0, c.from(), c.to(), c.fromInfo(), c.toInfo(),
                c.fromDisruption(), c.toDisruption(), true));

        assertThat(new MustNotBeCheckedIn().refusal(ctx)).hasValueSatisfying(r -> assertThat(r).contains("checked in"));
    }
}
