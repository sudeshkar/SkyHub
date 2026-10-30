package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefundBackoffTest {

    @Test
    void should_double_the_wait_each_time_from_five_minutes() {
        assertThat(RefundBackoff.delayAfter(1)).isEqualTo(Duration.ofMinutes(5));
        assertThat(RefundBackoff.delayAfter(2)).isEqualTo(Duration.ofMinutes(10));
        assertThat(RefundBackoff.delayAfter(3)).isEqualTo(Duration.ofMinutes(20));
        assertThat(RefundBackoff.delayAfter(6)).isEqualTo(Duration.ofMinutes(160));
    }

    @Test
    void should_never_wait_more_than_four_hours_however_many_attempts() {
        assertThat(RefundBackoff.delayAfter(7)).isEqualTo(Duration.ofHours(4));
        assertThat(RefundBackoff.delayAfter(RefundBackoff.MAX_ATTEMPTS)).isEqualTo(Duration.ofHours(4));
        assertThat(RefundBackoff.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(4));   // no overflow
    }

    @Test
    void should_keep_trying_for_about_a_day_before_giving_up() {
        Duration total = Duration.ZERO;
        for (int attempt = 1; attempt < RefundBackoff.MAX_ATTEMPTS; attempt++) {
            total = total.plus(RefundBackoff.delayAfter(attempt));
        }
        assertThat(total).isBetween(Duration.ofHours(20), Duration.ofHours(36));
    }

    @Test
    void should_refuse_a_nonsense_attempt_count() {
        assertThatIllegalArgumentException().isThrownBy(() -> RefundBackoff.delayAfter(0));
    }
}
