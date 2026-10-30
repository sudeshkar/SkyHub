package com.skyhub.flightsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Search follows flight disruptions (ADR-0025), on real PostgreSQL and a real in-process Kafka broker: a cancellation
 * published on the flight-disruptions topic removes the flight from search results, a delay shows beside the scheduled
 * time, and the flight is still found by lookup (bookings on it must be able to see it). Dates are relative to today,
 * because the board forgets flights that left long ago.
 */
@SpringBootTest(properties = "skyhub.flight-status.listener.enabled=true")
@EmbeddedKafka(topics = "skyhub.flight.disruptions", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class FlightStatusIntegrationTest {

    private static final String TOPIC = "skyhub.flight.disruptions";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    private static String day(int plusDays) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(plusDays).toString();
    }

    private static String event(String flight, String date, String kind, String revised) {
        return """
                {"eventId":"%s","type":"flight.disrupted","version":1,"occurredAt":"%s","flightNumber":"%s",
                 "departureDate":"%s","disruption":"%s"%s,"reason":"Test"}"""
                .formatted(UUID.randomUUID(), java.time.Instant.now(), flight, date, kind,
                        revised == null ? "" : ",\"revisedDeparture\":\"" + revised + "\"");
    }

    private List<String> directFlights(String date) throws Exception {
        String body = mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "DXB").param("date", date))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.stops == 0)].legs[0].flightNumber");
    }

    @Test
    void should_stop_offering_a_flight_once_it_is_cancelled_and_show_a_delay_beside_the_schedule() throws Exception {
        String cancelledDay = day(8);
        String delayedDay = day(9);
        assertThat(directFlights(cancelledDay)).contains("SK501");

        kafka.send(TOPIC, "SK501@" + cancelledDay, event("SK501", cancelledDay, "CANCELLED", null));
        kafka.send(TOPIC, "SK501@" + delayedDay, event("SK501", delayedDay, "DELAYED", delayedDay + "T20:00:00Z"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(directFlights(cancelledDay)).doesNotContain("SK501"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/itineraries").param("origin", "CMB").param("destination", "DXB").param("date", delayedDay))
                        .andExpect(jsonPath("$[?(@.legs[0].flightNumber == 'SK501' && @.stops == 0)].legs[0].status").value("DELAYED"))
                        .andExpect(jsonPath("$[?(@.legs[0].flightNumber == 'SK501' && @.stops == 0)].legs[0].revisedDeparture").value(delayedDay + "T20:00:00Z")));
        assertThat(directFlights(day(10))).contains("SK501");   // the next day is untouched
    }

    @Test
    void should_still_find_a_cancelled_flight_by_lookup_and_say_it_is_cancelled() throws Exception {
        String date = day(11);
        kafka.send(TOPIC, "SK501@" + date, event("SK501", date, "CANCELLED", null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                mvc.perform(get("/api/v1/flights/SK501/departures/" + date))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CANCELLED")));
    }

    @Test
    void should_ignore_a_message_that_is_not_an_event_and_carry_on() throws Exception {
        String date = day(12);
        kafka.send(TOPIC, "junk", "this is not json");
        kafka.send(TOPIC, "SK501@" + date, event("SK501", date, "CANCELLED", null));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(directFlights(date)).doesNotContain("SK501"));
    }
}
