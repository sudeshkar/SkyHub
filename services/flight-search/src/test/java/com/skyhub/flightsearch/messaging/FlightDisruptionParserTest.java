package com.skyhub.flightsearch.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.flightsearch.domain.FlightDisruption;
import com.skyhub.flightsearch.domain.FlightStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reads the same example the booking service's tests check its output against: the two cannot drift apart. */
class FlightDisruptionParserTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/flight-disrupted.v1.example.json");

    @Test
    void should_read_the_event_the_booking_service_publishes() throws Exception {
        FlightDisruption event = FlightDisruptionParser.parse(Files.readString(EXAMPLE));

        assertThat(event.eventId()).isEqualTo(UUID.fromString("4f8a2b6c-91d3-4e7a-8c52-3b1d9e6f7a10"));
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
        assertThat(event.flightNumber()).isEqualTo("SK501");
        assertThat(event.departureDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(event.status()).isEqualTo(FlightStatus.delayedTo(OffsetDateTime.parse("2026-11-02T14:30:00Z")));
    }

    @Test
    void should_read_a_cancellation_which_has_no_new_time() throws Exception {
        String cancelled = Files.readString(EXAMPLE).replace("\"DELAYED\"", "\"CANCELLED\"").replaceAll("\\s*\"revisedDeparture\"[^,]*,", "");

        assertThat(FlightDisruptionParser.parse(cancelled).status().isCancelled()).isTrue();
    }

    @Test
    void should_ignore_fields_added_later() throws Exception {
        String withExtra = Files.readString(EXAMPLE).replaceFirst("\\{", "{\"gate\": \"B7\", ");

        assertThat(FlightDisruptionParser.parse(withExtra).flightNumber()).isEqualTo("SK501");
    }

    @Test
    void should_tell_the_kind_of_event_without_reading_the_rest() throws Exception {
        assertThat(FlightDisruptionParser.typeOf(Files.readString(EXAMPLE))).isEqualTo("flight.disrupted");
    }

    @Test
    void should_refuse_anything_that_cannot_be_acted_on() throws Exception {
        String valid = Files.readString(EXAMPLE);
        for (String broken : new String[] {
                "not json", "{}",
                valid.replace("\"flight.disrupted\"", "\"flight.other\""),
                valid.replace("\"DELAYED\"", "\"EXPLODED\""),
                valid.replaceAll("\\s*\"revisedDeparture\"[^,]*,", ""),                  // a delay with no new time
                valid.replace("\"flightNumber\": \"SK501\",", ""),
                valid.replace("\"eventId\": \"4f8a2b6c-91d3-4e7a-8c52-3b1d9e6f7a10\",", "")}) {
            assertThatExceptionOfType(InvalidFlightEventException.class).as(broken)
                    .isThrownBy(() -> FlightDisruptionParser.parse(broken));
        }
    }
}
