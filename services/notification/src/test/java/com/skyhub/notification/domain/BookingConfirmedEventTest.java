package com.skyhub.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reads the same example file the booking service's tests check its output against: the two cannot drift apart. */
class BookingConfirmedEventTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");

    @Test
    void should_read_the_event_the_booking_service_publishes() throws Exception {
        BookingConfirmedEvent event = EventParser.parse(Files.readString(EXAMPLE), BookingConfirmedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11"));
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-30T10:00:00Z"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.contactEmail()).isEqualTo("priya@example.com");
        assertThat(event.passengers()).containsExactly(new BookingConfirmedEvent.Passenger("Priya", "Perera"));
        assertThat(event.segments()).containsExactly(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)));
        assertThat(event.totalAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(event.currency()).isEqualTo("USD");
    }

    @Test
    void should_ignore_fields_added_later_so_the_producer_can_grow_the_event() throws Exception {
        String withExtra = Files.readString(EXAMPLE).replaceFirst("\\{", "{\"seatClass\": \"economy\", ");

        assertThat(EventParser.parse(withExtra, BookingConfirmedEvent.class).locator()).isEqualTo("ABC234");
    }

    @Test
    void should_tell_which_kind_of_event_it_is_without_reading_the_rest() throws Exception {
        assertThat(EventParser.typeOf(Files.readString(EXAMPLE))).isEqualTo("booking.confirmed");
        assertThat(EventParser.typeOf("{\"type\":\"flight.disrupted\"}")).isEqualTo("flight.disrupted");
    }

    @Test
    void should_refuse_an_event_that_cannot_be_acted_on() {
        for (String broken : new String[] {
                "not json",
                "{}",
                "{\"eventId\":\"0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11\",\"type\":\"booking.confirmed\"}",
                "{\"eventId\":\"nope\",\"type\":\"booking.confirmed\",\"locator\":\"ABC234\",\"contactEmail\":\"a@b.co\","
                        + "\"passengers\":[],\"segments\":[],\"totalAmount\":1,\"currency\":\"USD\"}"}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken).isThrownBy(() -> EventParser.parse(broken, BookingConfirmedEvent.class));
        }
    }
}
