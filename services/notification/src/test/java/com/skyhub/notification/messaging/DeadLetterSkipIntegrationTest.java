package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.notification.PostgresTestcontainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Leaving one parked message out of the replay (ADR-0037), on a real in-process broker and PostgreSQL. Messages are parked with
 * a distinct reason each, so a test can find the offset of the one it wants by reading the listing, as an operator would.
 */
@SpringBootTest(properties = {"skyhub.notifications.email.mode=log", "management.server.port=8083"})   // same port as the app: MockMvc serves the actuator
@AutoConfigureMockMvc
@EmbeddedKafka(topics = {"skyhub.booking.events", "skyhub.booking.events.DLT"}, partitions = 2,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PostgresTestcontainer.class)
class DeadLetterSkipIntegrationTest {

    private static final Operator ME = Operator.named("priya.perera");

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeadLetterReplayer replayer;

    @Autowired
    private DeadLetterSkips skips;

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void startFromEmpty() {
        replayer.replay(1000, ME);
        jdbc.update("DELETE FROM skipped_dead_letter");
    }

    private UUID park(String locator, String reason) throws Exception {
        UUID id = UUID.randomUUID();
        String body = Files.readString(EXAMPLE).replace("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11", id.toString()).replace("ABC234", locator);
        ProducerRecord<String, String> record = new ProducerRecord<>("skyhub.booking.events.DLT", null, locator, body);
        record.headers().add("kafka_dlt-original-topic", "skyhub.booking.events".getBytes());
        record.headers().add("kafka_dlt-exception-message", reason.getBytes());
        kafka.send(record).get();
        return id;
    }

    private ParkedMessage find(String reason) {
        return replayer.peek(1000).messages().stream().filter(m -> m.error().equals(reason)).findFirst().orElseThrow();
    }

    private long rows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sent_notification WHERE event_id = ?", Long.class, id);
    }

    private long skipRows() {
        return jdbc.queryForObject("SELECT count(*) FROM skipped_dead_letter", Long.class);
    }

    @Test
    void should_replay_everything_except_the_skipped_message_and_then_forget_the_skip() throws Exception {
        UUID a = park("SKIPA1", "Mail server did not answer");
        UUID b = park("SKIPB2", "Event type removed; can never be parsed");
        UUID c = park("SKIPC3", "Mail server did not answer too");
        ParkedMessage bad = find("Event type removed; can never be parsed");
        skips.skip(bad.partition(), bad.offset(), "Can never succeed: sent by the retired v1 producer", ME);

        var result = replayer.replay(1000, ME);

        assertThat(result.replayed()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.remaining()).isZero();
        await().atMost(Duration.ofSeconds(30)).until(() -> rows(a) == 1 && rows(c) == 1);
        assertThat(rows(b)).isZero();                      // never republished, so never recorded
        assertThat(skipRows()).isZero();                   // the replay passed it, so the row is gone
        assertThat(replayer.peek(10).parked()).isZero();   // and it does not come back on the next replay
        assertThat(replayer.replay(1000, ME).replayed()).isZero();
    }

    @Test
    void should_show_in_the_listing_which_parked_messages_the_next_replay_will_leave_out() throws Exception {
        park("SHOW01", "Reason one");
        park("SHOW02", "Reason two");
        ParkedMessage two = find("Reason two");
        skips.skip(two.partition(), two.offset(), "Not worth replaying", ME);

        var listed = replayer.peek(10).messages();

        assertThat(listed).filteredOn(m -> m.error().equals("Reason two")).extracting(ParkedMessage::skipped).containsExactly(true);
        assertThat(listed).filteredOn(m -> m.error().equals("Reason one")).extracting(ParkedMessage::skipped).containsExactly(false);
        assertThat(listed.toString()).doesNotContain("priya@example.com");
    }

    @Test
    void should_replay_a_message_again_when_its_skip_is_taken_off_before_the_replay() throws Exception {
        UUID id = park("UNDO01", "Looked bad, was fine");
        ParkedMessage message = find("Looked bad, was fine");
        skips.skip(message.partition(), message.offset(), "Thought it was malformed", ME);
        assertThat(skips.unskip(message.partition(), message.offset(), ME)).isTrue();

        assertThat(replayer.replay(1000, ME).replayed()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(id) == 1);
    }

    @Test
    void should_refuse_to_skip_a_message_that_is_not_waiting() throws Exception {
        park("GONE01", "Soon replayed");
        ParkedMessage message = find("Soon replayed");
        replayer.replay(1000, ME);   // now it has been taken

        assertThatExceptionOfType(InvalidEndpointRequestException.class)
                .isThrownBy(() -> skips.skip(message.partition(), message.offset(), "too late", ME));
        assertThatExceptionOfType(InvalidEndpointRequestException.class)
                .isThrownBy(() -> skips.skip(0, 9_999_999, "beyond the end of the topic", ME));
        assertThatExceptionOfType(InvalidEndpointRequestException.class)
                .isThrownBy(() -> skips.skip(99, 0, "no such partition", ME));
        assertThat(skipRows()).isZero();
    }

    @Test
    void should_work_through_the_operators_door_over_http() throws Exception {
        UUID keep = park("HTTP01", "Fine one");
        park("HTTP02", "Bad one");
        ParkedMessage bad = find("Bad one");

        mvc.perform(post("/actuator/deadletterskips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":%d,\"offset\":%d,\"reason\":\"Retired producer\",\"operator\":\"priya.perera\"}".formatted(bad.partition(), bad.offset())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("Retired producer"))
                .andExpect(jsonPath("$.skippedBy").value("priya.perera"));
        mvc.perform(post("/actuator/deadletterskips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":0,\"offset\":9999999,\"reason\":\"typo\",\"operator\":\"priya.perera\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/actuator/deadletterskips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":%d,\"offset\":%d,\"operator\":\"priya.perera\"}".formatted(bad.partition(), bad.offset())))
                .andExpect(status().isBadRequest());   // a reason is required
        mvc.perform(post("/actuator/deadletterskips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":%d,\"offset\":%d,\"reason\":\"No name given\"}".formatted(bad.partition(), bad.offset())))
                .andExpect(status().isBadRequest());   // and so is a name
        mvc.perform(post("/actuator/deadletters").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());   // a replay too, and nothing was replayed
        mvc.perform(get("/actuator/deadletterskips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offset").value((int) bad.offset()))
                .andExpect(jsonPath("$[0].skippedBy").value("priya.perera"));

        mvc.perform(post("/actuator/deadletters").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"priya.perera\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
        await().atMost(Duration.ofSeconds(30)).until(() -> rows(keep) == 1);

        mvc.perform(delete("/actuator/deadletterskips").param("partition", "0").param("offset", "1"))
                .andExpect(status().isBadRequest());   // taking one off the list needs a name too
        mvc.perform(delete("/actuator/deadletterskips").param("partition", "0").param("offset", "1").param("operator", "priya.perera"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(false));   // nothing was listed there
    }
}
