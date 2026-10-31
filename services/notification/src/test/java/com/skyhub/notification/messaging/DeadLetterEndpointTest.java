package com.skyhub.notification.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.notification.PostgresTestcontainer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

/** The operator's door to the replay tool (ADR-0032): the actuator endpoint, with and without a limit. */
@SpringBootTest(properties = {"skyhub.notifications.email.mode=log", "management.server.port=8083"})   // same port as the app: MockMvc serves the actuator
@AutoConfigureMockMvc
@EmbeddedKafka(topics = {"skyhub.booking.events", "skyhub.booking.events.DLT"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PostgresTestcontainer.class)
class DeadLetterEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Test
    void should_list_what_is_parked_and_replay_it_over_http_with_or_without_a_limit() throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>("skyhub.booking.events.DLT", null, "K1", "{\"type\":\"seat.changed\"}");
        record.headers().add("kafka_dlt-exception-message", "boom".getBytes());
        kafka.send(record).get();

        mvc.perform(get("/actuator/deadletters").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parked").value(1))
                .andExpect(jsonPath("$.messages[0].eventType").value("seat.changed"))
                .andExpect(jsonPath("$.messages[0].error").value("boom"));

        mvc.perform(post("/actuator/deadletters").contentType(MediaType.APPLICATION_JSON).content("{\"limit\": 5, \"operator\": \"priya.perera\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.remaining").value(0));

        mvc.perform(post("/actuator/deadletters").contentType(MediaType.APPLICATION_JSON).content("{\"operator\": \"priya.perera\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(0));
    }
}
