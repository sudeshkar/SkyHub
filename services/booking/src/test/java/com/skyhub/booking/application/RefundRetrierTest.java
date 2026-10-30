package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skyhub.booking.application.RefundSource.Pending;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundRetrierTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private static class FakeSource implements RefundSource {
        final String name;
        final List<Pending> due;
        final List<Long> settled = new ArrayList<>();
        boolean broken;

        FakeSource(String name, Pending... due) {
            this.name = name;
            this.due = List.of(due);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Pending> claimDue(Instant now, int limit) {
            if (broken) {
                throw new IllegalStateException("database down");
            }
            return due;
        }

        @Override
        public void refunded(Pending pending, Instant now) {
            settled.add(pending.id());
        }
    }

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RefundRetrier retrier(RefundSource... sources) {
        return new RefundRetrier(List.of(sources), gateway, Clock.fixed(NOW, ZoneOffset.UTC), new RefundRetryMetrics(registry));
    }

    private double count(String source, String outcome) {
        return registry.get("skyhub.refund.retries").tag("source", source).tag("outcome", outcome).counter().count();
    }

    @Test
    void should_refund_what_is_due_from_every_source_and_record_it() {
        FakeSource booking = new FakeSource("failed-booking", new Pending(1, "ch_1", 1));
        FakeSource cancellation = new FakeSource("cancellation", new Pending(7, "ch_7", 2));

        int paid = retrier(booking, cancellation).sweep();

        assertThat(paid).isEqualTo(2);
        verify(gateway).refund("ch_1");
        verify(gateway).refund("ch_7");
        assertThat(booking.settled).containsExactly(1L);
        assertThat(cancellation.settled).containsExactly(7L);
        assertThat(count("failed-booking", "refunded")).isEqualTo(1);
    }

    @Test
    void should_leave_a_failed_refund_for_later_and_carry_on_with_the_others() {
        FakeSource source = new FakeSource("failed-booking", new Pending(1, "ch_1", 1), new Pending(2, "ch_2", 1));
        doThrow(new PaymentUnavailableException("no answer", null)).when(gateway).refund("ch_1");

        int paid = retrier(source).sweep();

        assertThat(paid).isEqualTo(1);
        assertThat(source.settled).containsExactly(2L);   // the failed one is not marked
        assertThat(count("failed-booking", "failed")).isEqualTo(1);
    }

    @Test
    void should_report_giving_up_only_on_the_last_attempt() {
        FakeSource source = new FakeSource("cancellation", new Pending(1, "ch_1", RefundBackoff.MAX_ATTEMPTS - 1),
                new Pending(2, "ch_2", RefundBackoff.MAX_ATTEMPTS));
        doThrow(new PaymentUnavailableException("no answer", null)).when(gateway).refund(anyString());

        retrier(source).sweep();

        assertThat(count("cancellation", "failed")).isEqualTo(1);
        assertThat(count("cancellation", "gave_up")).isEqualTo(1);
    }

    @Test
    void should_keep_going_with_other_sources_when_one_cannot_be_read() {
        FakeSource broken = new FakeSource("failed-booking");
        broken.broken = true;
        FakeSource healthy = new FakeSource("cancellation", new Pending(7, "ch_7", 1));

        assertThat(retrier(broken, healthy).sweep()).isEqualTo(1);
    }

    @Test
    void should_not_call_the_provider_when_nothing_is_due() {
        assertThat(retrier(new FakeSource("failed-booking")).sweep()).isZero();

        verify(gateway, never()).refund(any());
    }

    @Test
    void should_count_a_refund_as_paid_even_when_writing_it_down_fails_because_trying_again_is_harmless() {
        FakeSource source = new FakeSource("failed-booking", new Pending(1, "ch_1", 1)) {
            @Override
            public void refunded(Pending pending, Instant now) {
                throw new IllegalStateException("database down");
            }
        };

        assertThat(retrier(source).sweep()).isEqualTo(1);
    }
}
