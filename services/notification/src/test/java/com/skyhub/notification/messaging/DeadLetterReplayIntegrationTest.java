package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.skyhub.notification.PostgresTestcontainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Putting parked messages back (ADR-0032), on a real in-process broker and PostgreSQL. A message is placed on the dead-letter topic
 * the way the error handler does it, replayed, and shown to become exactly one recorded email.
 */
@SpringBootTest(properties = {"skyhub.notifications.email.mode=log"})
@EmbeddedKafka(topics = {"skyhub.booking.events", "skyhub.booking.events.DLT"}, partitions = 2,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PostgresTestcontainer.class)
class DeadLetterReplayIntegrationTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeadLetterReplayer replayer;

    private UUID park(String locator) throws Exception {
        UUID id = UUID.randomUUID();
        String body = Files.readString(EXAMPLE).replace("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11", id.toString()).replace("ABC234", locator);
        ProducerRecord<String, String> record = new ProducerRecord<>("skyhub.booking.events.DLT", null, locator, body);
        record.headers().add("kafka_dlt-original-topic", "skyhub.booking.events".getBytes());
        record.headers().add("kafka_dlt-exception-message", "Mail server did not answer".getBytes());
        kafka.send(record).get();
        return id;
    }

    private long rows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sent_notification WHERE event_id = ?", Long.class, id);
    }

    @Test
    void should_show_what_is_parked_without_taking_it_and_without_the_message_body() throws Exception {
        park("PEEK01");

        var first = replayer.peek(50);
        var second = replayer.peek(50);

        assertThat(first.parked()).isGreaterThanOrEqualTo(1);
        assertThat(first.messages()).extracting(ParkedMessage::error).contains("Mail server did not answer");
        assertThat(second.parked()).isEqualTo(first.parked());   // looking changes nothing
        assertThat(first.messages().toString()).doesNotContain("priya@example.com");
    }

    @Test
    void should_put_parked_messages_back_so_each_becomes_one_email_and_not_again_on_the_next_replay() throws Exception {
        UUID a = park("REPLAY1");
        UUID b = park("REPLAY2");

        var result = replayer.replay(1000);

        assertThat(result.replayed()).isGreaterThanOrEqualTo(2);
        await().atMost(Duration.ofSeconds(30)).until(() -> rows(a) == 1 && rows(b) == 1);
        assertThat(replayer.replay(1000).replayed()).isZero();   // they were taken: a second replay finds nothing new
        assertThat(replayer.peek(10).parked()).isZero();
    }

    @Test
    void should_replay_only_as_many_as_asked_and_leave_the_rest_for_later() throws Exception {
        replayer.replay(1000);   // start from empty
        UUID first = park("LIMIT01");
        UUID second = park("LIMIT02");
        UUID third = park("LIMIT03");

        assertThat(replayer.replay(1).replayed()).isEqualTo(1);
        assertThat(replayer.peek(10).parked()).isEqualTo(2);
        assertThat(replayer.replay(1000).replayed()).isEqualTo(2);

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(first) + rows(second) + rows(third) == 3);
    }

    @Test
    void should_never_replay_more_than_the_hard_limit_whatever_it_is_asked_for() {
        assertThat(replayer.peek(1_000_000).messages().size()).isLessThanOrEqualTo(DeadLetterReplayer.MAX_PER_CALL);
        assertThat(DeadLetterReplayer.clamp(0)).isEqualTo(DeadLetterReplayer.DEFAULT_PER_CALL);
        assertThat(DeadLetterReplayer.clamp(-5)).isEqualTo(DeadLetterReplayer.DEFAULT_PER_CALL);
        assertThat(DeadLetterReplayer.clamp(1_000_000)).isEqualTo(DeadLetterReplayer.MAX_PER_CALL);
    }

    @Test
    void should_refuse_a_second_replay_while_one_is_running_rather_than_do_the_same_work_twice() throws Exception {
        for (int i = 0; i < 30; i++) {
            park("BUSY" + i);
        }

        var one = CompletableFuture.supplyAsync(() -> replayer.replay(1000));
        var two = CompletableFuture.supplyAsync(() -> replayer.replay(1000));
        var a = one.get();
        var b = two.get();

        assertThat(a.replayed() + b.replayed()).isGreaterThanOrEqualTo(30);
        assertThat(a.busy() || b.busy() || a.replayed() == 0 || b.replayed() == 0).isTrue();
    }
}
