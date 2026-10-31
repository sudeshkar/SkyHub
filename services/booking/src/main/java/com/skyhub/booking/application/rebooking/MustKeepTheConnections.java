package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.application.rebooking.RebookingContext.Neighbour;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * A replacement must still let the traveller make the flights either side of it (ADR-0036): it must land at least
 * {@link #MINIMUM} before the booking's next flight leaves, and leave at least that long after the previous flight lands. Both
 * are judged on expected times (delays included). A neighbour that is cancelled is not a constraint, since it has no time to
 * meet and the traveller is being moved off it anyway. When a time that is needed is not known the move is refused, not guessed.
 */
public final class MustKeepTheConnections implements RebookingRule {

    /** The same figure the disruption agent's planner uses for the time needed between landing and the next flight. */
    static final Duration MINIMUM = Duration.ofMinutes(60);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC);

    @Override
    public Optional<String> refusal(RebookingContext context) {
        Optional<String> previous = context.previous().filter(n -> !isCancelled(n)).flatMap(n -> previousJoin(context, n));
        return previous.isPresent() ? previous : context.next().filter(n -> !isCancelled(n)).flatMap(n -> nextJoin(context, n));
    }

    /** The previous flight lands, then the replacement leaves. */
    private static Optional<String> previousJoin(RebookingContext context, Neighbour previous) {
        String before = previous.segment().flightNumber();
        String replacement = context.to().flightNumber();
        Optional<Instant> lands = ExpectedTimes.arrival(previous.info(), previous.disruption());
        if (lands.isEmpty()) {
            return Optional.of("The arrival time of " + before + " is not known, so its connection with " + replacement
                    + " cannot be checked");
        }
        Instant leaves = ExpectedTimes.departure(context.toInfo(), context.toDisruption());
        return tooTight(lands.get(), leaves)
                ? Optional.of(before + " would land at " + TIME.format(lands.get()) + ", " + gap(lands.get(), leaves) + replacement
                        + " leaves at " + TIME.format(leaves) + "; at least " + MINIMUM.toMinutes() + " are needed")
                : Optional.empty();
    }

    /** The replacement lands, then the next flight leaves. */
    private static Optional<String> nextJoin(RebookingContext context, Neighbour next) {
        String after = next.segment().flightNumber();
        String replacement = context.to().flightNumber();
        Optional<Instant> lands = ExpectedTimes.arrival(context.toInfo(), context.toDisruption());
        if (lands.isEmpty()) {
            return Optional.of("The arrival time of " + replacement + " is not known, so its connection with " + after
                    + " cannot be checked");
        }
        Instant leaves = ExpectedTimes.departure(next.info(), next.disruption());
        return tooTight(lands.get(), leaves)
                ? Optional.of(replacement + " would land at " + TIME.format(lands.get()) + ", " + gap(lands.get(), leaves) + after
                        + " leaves at " + TIME.format(leaves) + "; at least " + MINIMUM.toMinutes() + " are needed")
                : Optional.empty();
    }

    private static boolean tooTight(Instant lands, Instant leaves) {
        return Duration.between(lands, leaves).compareTo(MINIMUM) < 0;
    }

    /** "which leaves 20 minutes before " or, when the second flight has already gone, "which is after ". */
    private static String gap(Instant lands, Instant leaves) {
        Duration gap = Duration.between(lands, leaves);
        return gap.isNegative() ? "which is after " : "which leaves " + gap.toMinutes() + " minutes before ";
    }

    private static boolean isCancelled(Neighbour neighbour) {
        return neighbour.disruption().map(d -> d.isCancelled()).orElse(false);
    }
}
