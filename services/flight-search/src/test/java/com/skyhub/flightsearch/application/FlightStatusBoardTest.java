package com.skyhub.flightsearch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.flightsearch.domain.FlightDisruption;
import com.skyhub.flightsearch.domain.FlightStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FlightStatusBoardTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FlightStatusBoard board = new FlightStatusBoard(Clock.fixed(NOW, ZoneOffset.UTC), registry);

    private static FlightDisruption cancelled(String flight, LocalDate day, Instant at) {
        return new FlightDisruption(UUID.randomUUID(), "flight.disrupted", 1, at, flight, day, "CANCELLED", null, "Grounded");
    }

    private static FlightDisruption delayed(String flight, LocalDate day, Instant at, String revised) {
        return new FlightDisruption(UUID.randomUUID(), "flight.disrupted", 1, at, flight, day, "DELAYED",
                OffsetDateTime.parse(revised), "Weather");
    }

    @Test
    void should_report_every_flight_as_scheduled_until_told_otherwise() {
        assertThat(board.statusOf("SK501", DAY)).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(board.size()).isZero();
    }

    @Test
    void should_remember_a_cancellation_for_that_flight_and_date_only() {
        board.apply(cancelled("SK501", DAY, NOW));

        assertThat(board.statusOf("SK501", DAY).isCancelled()).isTrue();
        assertThat(board.statusOf("SK501", DAY.plusDays(1))).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(board.statusOf("SK502", DAY)).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void should_remember_a_delay_with_its_new_time() {
        board.apply(delayed("SK501", DAY, NOW, "2026-11-02T14:30:00Z"));

        assertThat(board.statusOf("SK501", DAY)).isEqualTo(FlightStatus.delayedTo(OffsetDateTime.parse("2026-11-02T14:30:00Z")));
    }

    @Test
    void should_follow_the_latest_report_when_a_delay_becomes_a_cancellation() {
        board.apply(delayed("SK501", DAY, NOW, "2026-11-02T14:30:00Z"));
        board.apply(cancelled("SK501", DAY, NOW.plusSeconds(60)));

        assertThat(board.statusOf("SK501", DAY).isCancelled()).isTrue();
    }

    private static FlightDisruption cleared(String flight, LocalDate day, Instant at) {
        return new FlightDisruption(UUID.randomUUID(), "flight.disrupted", 1, at, flight, day, "CLEARED", null, null);
    }

    @Test
    void should_show_a_flight_as_scheduled_again_once_its_disruption_is_withdrawn() {
        board.apply(cancelled("SK501", DAY, NOW));

        board.apply(cleared("SK501", DAY, NOW.plusSeconds(60)));

        assertThat(board.statusOf("SK501", DAY)).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void should_not_let_a_late_older_disruption_bring_back_a_withdrawn_one() {
        board.apply(cleared("SK501", DAY, NOW.plusSeconds(60)));

        FlightStatusBoard.Outcome outcome = board.apply(cancelled("SK501", DAY, NOW));

        assertThat(outcome).isEqualTo(FlightStatusBoard.Outcome.STALE);
        assertThat(board.statusOf("SK501", DAY)).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void should_ignore_an_older_report_that_arrives_late_such_as_a_replay() {
        board.apply(cancelled("SK501", DAY, NOW.plusSeconds(60)));

        FlightStatusBoard.Outcome outcome = board.apply(delayed("SK501", DAY, NOW, "2026-11-02T14:30:00Z"));

        assertThat(outcome).isEqualTo(FlightStatusBoard.Outcome.STALE);
        assertThat(board.statusOf("SK501", DAY).isCancelled()).isTrue();
    }

    @Test
    void should_not_keep_flights_that_left_long_ago_so_memory_stays_bounded() {
        FlightStatusBoard.Outcome outcome = board.apply(cancelled("SK501", TODAY.minusDays(5), NOW));

        assertThat(outcome).isEqualTo(FlightStatusBoard.Outcome.EXPIRED);
        assertThat(board.size()).isZero();
    }

    @Test
    void should_forget_flights_that_have_gone_when_purged() {
        board.apply(cancelled("SK501", TODAY, NOW));          // keeping yesterday and today
        board.apply(cancelled("SK502", DAY, NOW));
        Clock later = Clock.fixed(NOW.plusSeconds(5 * 86_400), ZoneOffset.UTC);

        int removed = new FlightStatusBoard(later, registry).purge();   // a fresh board has nothing to purge
        assertThat(removed).isZero();
        assertThat(board.purgeAsOf(TODAY.plusDays(3))).isEqualTo(1);

        assertThat(board.statusOf("SK501", TODAY)).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(board.statusOf("SK502", DAY).isCancelled()).isTrue();
    }

    @Test
    void should_count_what_it_applies_and_show_its_size() {
        board.apply(cancelled("SK501", DAY, NOW));
        board.apply(cancelled("SK501", DAY, NOW.minusSeconds(5)));

        assertThat(registry.get("skyhub.flight.status.events").tag("outcome", "applied").counter().count()).isEqualTo(1);
        assertThat(registry.get("skyhub.flight.status.events").tag("outcome", "stale").counter().count()).isEqualTo(1);
        assertThat(registry.get("skyhub.flight.status.board.size").gauge().value()).isEqualTo(1);
    }

    @Test
    void should_stay_correct_when_many_threads_report_and_read_at_once() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            int thread = t;
            pool.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    board.apply(cancelled("SK" + (100 + i % 50), DAY.plusDays(thread % 3), NOW.plusSeconds(i)));
                    board.statusOf("SK" + (100 + i % 50), DAY);
                }
                done.countDown();
            });
        }
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(board.size()).isEqualTo(150);   // 50 flights on 3 dates, however the threads interleaved
    }

    @Test
    void should_answer_a_lookup_in_constant_time_however_many_flights_are_disrupted() {
        for (int i = 0; i < 50_000; i++) {
            board.apply(cancelled("SK" + i, DAY, NOW));
        }

        long started = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            board.statusOf("SK" + (i % 50_000), DAY);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(2_000);
    }
}
