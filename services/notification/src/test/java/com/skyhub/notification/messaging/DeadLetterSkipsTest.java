package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.notification.application.SkippedLetters;
import com.skyhub.notification.application.SkippedLetters.Skip;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;

class DeadLetterSkipsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    /** Keeps the list in memory, the way the table would. */
    private static final class FakeStore implements SkippedLetters {
        final Map<String, Skip> rows = new LinkedHashMap<>();

        private static String key(int partition, long offset) {
            return partition + "/" + offset;
        }

        @Override
        public void skip(int partition, long offset, String reason, Instant at) {
            rows.putIfAbsent(key(partition, offset), new Skip(partition, offset, reason, at));
        }

        @Override
        public boolean unskip(int partition, long offset) {
            return rows.remove(key(partition, offset)) != null;
        }

        @Override
        public Set<Long> skippedIn(int partition, long fromOffset, long toOffset) {
            return rows.values().stream().filter(s -> s.partition() == partition && s.offset() >= fromOffset && s.offset() <= toOffset)
                    .map(Skip::offset).collect(Collectors.toSet());
        }

        @Override
        public void forget(int partition, Collection<Long> offsets) {
            offsets.forEach(o -> rows.remove(key(partition, o)));
        }

        @Override
        public List<Skip> all() {
            return new ArrayList<>(rows.values());
        }
    }

    private final FakeStore store = new FakeStore();
    private final List<String> waiting = new ArrayList<>(List.of("0/5", "1/9"));
    private final DeadLetterSkips skips = new DeadLetterSkips(store, (partition, offset) -> waiting.contains(partition + "/" + offset),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void should_list_a_waiting_message_to_be_left_out_with_the_reason_and_the_time() {
        Skip skip = skips.skip(0, 5, "Event type was removed in v3; can never be parsed");

        assertThat(skip).isEqualTo(new Skip(0, 5, "Event type was removed in v3; can never be parsed", NOW));
        assertThat(store.all()).containsExactly(skip);
    }

    @Test
    void should_refuse_a_message_that_is_not_waiting_and_store_nothing() {
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(0, 6, "typo"))
                .withMessageContaining("not waiting");
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(7, 5, "no such partition"));
        assertThat(store.all()).isEmpty();
    }

    @Test
    void should_require_a_reason_of_reasonable_length() {
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(0, 5, null));
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(0, 5, "   "));
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(0, 5, "x".repeat(201)));
        assertThat(store.all()).isEmpty();
        assertThat(skips.skip(0, 5, "x".repeat(200)).reason()).hasSize(200);
    }

    @Test
    void should_hide_an_email_address_in_the_reason_before_storing_it() {
        Skip skip = skips.skip(0, 5, "Bounced for priya@example.com; mailbox closed");

        assertThat(skip.reason()).doesNotContain("priya").doesNotContain("example.com").contains("mailbox closed");
        assertThat(store.all().getFirst().reason()).doesNotContain("priya");
    }

    @Test
    void should_refuse_a_negative_partition_or_offset() {
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(-1, 5, "x"));
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> skips.skip(0, -1, "x"));
    }

    @Test
    void should_make_skipping_twice_the_same_as_once() {
        skips.skip(0, 5, "first reason");
        skips.skip(0, 5, "second reason");

        assertThat(store.all()).hasSize(1);
        assertThat(store.all().getFirst().reason()).isEqualTo("first reason");
    }

    @Test
    void should_take_a_message_off_the_list_again_and_say_whether_it_was_there() {
        skips.skip(0, 5, "a reason");

        assertThat(skips.unskip(0, 5)).isTrue();
        assertThat(skips.unskip(0, 5)).isFalse();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void should_show_everything_that_is_listed() {
        skips.skip(0, 5, "one");
        skips.skip(1, 9, "two");

        assertThat(skips.listed()).extracting(Skip::reason).containsExactly("one", "two");
    }
}
