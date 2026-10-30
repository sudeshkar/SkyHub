package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final String TOPIC = "skyhub.booking.events";

    @Mock
    private OutboxEventRepository outbox;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private KafkaOutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new KafkaOutboxRelay(outbox, kafka, cipher, Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction(), registry, TOPIC, 50, Duration.ofSeconds(1), Duration.ofDays(7));
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private OutboxEvent pending(String locator, String json) {
        return OutboxEvent.pending(UUID.randomUUID(), locator, "booking.confirmed", cipher.encrypt(json), NOW.minusSeconds(5));
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void should_publish_pending_events_in_order_decrypted_and_keyed_by_booking() {
        OutboxEvent first = pending("ABC234", "{\"n\":1}");
        OutboxEvent second = pending("XYZ789", "{\"n\":2}");
        when(outbox.lockPending(50)).thenReturn(List.of(first, second));

        relay.publishPending();

        InOrder order = inOrder(kafka);
        order.verify(kafka).send(TOPIC, "ABC234", "{\"n\":1}");
        order.verify(kafka).send(TOPIC, "XYZ789", "{\"n\":2}");
        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isTrue();
        verify(outbox).saveAll(List.of(first, second));
        assertThat(count("skyhub.outbox.published")).isEqualTo(2);
    }

    @Test
    void should_leave_events_pending_when_the_broker_does_not_answer() {
        OutboxEvent event = pending("ABC234", "{\"n\":1}");
        when(outbox.lockPending(50)).thenReturn(List.of(event));
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending(); // must not throw: the schedule has to keep running

        assertThat(event.isPublished()).isFalse();
        assertThat(count("skyhub.outbox.publish.failures")).isEqualTo(1);
    }

    @Test
    void should_stop_at_the_first_failure_so_later_events_never_overtake_it() {
        OutboxEvent first = pending("ABC234", "{\"n\":1}");
        OutboxEvent second = pending("XYZ789", "{\"n\":2}");
        when(outbox.lockPending(50)).thenReturn(List.of(first, second));
        when(kafka.send(eq(TOPIC), eq("ABC234"), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();

        verify(kafka, never()).send(eq(TOPIC), eq("XYZ789"), any(String.class));
        assertThat(first.isPublished()).isFalse();
        assertThat(second.isPublished()).isFalse();
    }

    @Test
    void should_keep_what_was_sent_before_a_failure() {
        OutboxEvent first = pending("ABC234", "{\"n\":1}");
        OutboxEvent second = pending("XYZ789", "{\"n\":2}");
        when(outbox.lockPending(50)).thenReturn(List.of(first, second));
        when(kafka.send(eq(TOPIC), eq("XYZ789"), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();

        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isFalse();
        verify(outbox).saveAll(List.of(first));
    }

    @Test
    void should_do_nothing_when_there_is_nothing_to_send() {
        when(outbox.lockPending(50)).thenReturn(List.of());

        relay.publishPending();

        verify(kafka, never()).send(any(String.class), any(String.class), any(String.class));
    }

    @Test
    void should_purge_events_published_more_than_a_week_ago() {
        relay.purgePublished();

        verify(outbox).deletePublishedBefore(NOW.minus(Duration.ofDays(7)));
    }
}
