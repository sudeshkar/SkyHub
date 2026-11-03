package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skyhub.notification.PostgresTestcontainer;
import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailUnavailableException;
import com.skyhub.notification.integration.LoggingEmailSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * A message that fails to send moves to a delayed retry topic instead of holding up the partition (ADR-0065), on a real in-process
 * broker and real PostgreSQL. The retry delay here is 3 seconds, long enough that anything that finishes sooner cannot have waited for
 * a retry: that is how "not held up" is proved.
 */
@SpringBootTest(properties = {"skyhub.notifications.email.mode=log", "skyhub.notifications.retry.attempts=3",
        "skyhub.notifications.retry.delay-ms=3000", "skyhub.notifications.retry.multiplier=1.0", "skyhub.notifications.retry.jitter-ms=0",
        "skyhub.notifications.retry.partitions=3"})
@EmbeddedKafka(topics = {"skyhub.booking.events", "skyhub.booking.events.DLT"}, partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PostgresTestcontainer.class)
class NonBlockingRetryIntegrationTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");
    private static final String TOPIC = "skyhub.booking.events";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private DeadLetterReplayer replayer;

    @MockitoSpyBean
    private LoggingEmailSender email;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private static String event(UUID id, String locator) throws Exception {
        return Files.readString(EXAMPLE).replace("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11", id.toString()).replace("ABC234", locator);
    }

    private long rows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sent_notification WHERE event_id = ?", Long.class, id);
    }

    private double deadLettered() {
        var counter = registry.find("skyhub.notifications.dead_lettered").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_not_hold_up_healthy_events_behind_failing_ones_and_send_the_failed_ones_once_the_mail_server_is_back() throws Exception {
        boolean[] mailServerDown = {true};
        doAnswer(call -> {
            EmailMessage message = call.getArgument(0);
            if (mailServerDown[0] && message.subject().contains("DOWN")) {
                throw new EmailUnavailableException("mail server did not answer", null);
            }
            return call.callRealMethod();
        }).when(email).send(any(EmailMessage.class));
        List<UUID> failing = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            failing.add(id);
            kafka.send(TOPIC, "DOWN" + String.format("%02d", i), event(id, "DOWN" + String.format("%02d", i)));
        }
        UUID healthy = UUID.randomUUID();
        kafka.send(TOPIC, "HEALTHY1", event(healthy, "HEALTHY1"));

        // Well inside the 3 second retry delay: the healthy event did not wait for any of the 20 failing ones.
        await().atMost(Duration.ofSeconds(2)).until(() -> rows(healthy) == 1);
        assertThat(failing).allMatch(id -> rows(id) == 0);

        mailServerDown[0] = false;   // the mail server comes back while the retries are waiting
        await().atMost(Duration.ofSeconds(20)).until(() -> failing.stream().allMatch(id -> rows(id) == 1));
    }

    @Test
    void should_send_one_email_when_the_mail_server_comes_back_during_the_retries() throws Exception {
        UUID id = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            EmailMessage message = call.getArgument(0);
            if (message.subject().contains("FLAKY1") && attempts.incrementAndGet() <= 2) {
                throw new EmailUnavailableException("mail server did not answer", null);
            }
            return call.callRealMethod();
        }).when(email).send(any(EmailMessage.class));

        kafka.send(TOPIC, "FLAKY1", event(id, "FLAKY1"));

        await().atMost(Duration.ofSeconds(20)).until(() -> rows(id) == 1);
        assertThat(attempts.get()).isEqualTo(3);   // two failures, then the one that worked
        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    void should_park_a_message_after_its_last_attempt_count_it_and_let_a_person_see_it() throws Exception {
        UUID id = UUID.randomUUID();
        doAnswer(call -> {
            EmailMessage message = call.getArgument(0);
            if (message.subject().contains("NEVER1")) {
                throw new EmailUnavailableException("mail server did not answer", null);
            }
            return call.callRealMethod();
        }).when(email).send(any(EmailMessage.class));
        double before = deadLettered();
        String body = event(id, "NEVER1");

        kafka.send(TOPIC, "NEVER1", body);

        assertThat(parkedWithin(body, Duration.ofSeconds(25))).isTrue();
        assertThat(rows(id)).isZero();
        await().atMost(Duration.ofSeconds(10)).until(() -> deadLettered() >= before + 1);
        // The parked-message tooling (ADR-0032) still finds it, on the same <topic>.DLT.
        assertThat(replayer.peek(1000).messages()).extracting(ParkedMessage::error).anyMatch(error -> error.contains("mail server did not answer"));
    }

    @Test
    void should_park_a_message_that_can_never_succeed_at_once_without_retrying_or_sending() throws Exception {
        String poison = "{\"type\":\"booking.confirmed\",\"note\":\"" + UUID.randomUUID() + "\"}";

        kafka.send(TOPIC, "POISON1", poison);

        // Inside the 3 second retry delay: it was not retried first.
        assertThat(parkedWithin(poison, Duration.ofSeconds(2))).isTrue();
        verify(email, never()).send(any(EmailMessage.class));
    }

    /** Whether this message reaches the dead-letter topic within the time, polled with one consumer so the time is the real limit. */
    private boolean parkedWithin(String body, Duration within) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "retry-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            long until = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < until) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.value().equals(body)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
