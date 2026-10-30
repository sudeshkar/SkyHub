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

/** Reads the same example files the booking service's tests check its output against: the two cannot drift apart. */
class NotificationEventsTest {

    private static final Path CANCELLED = Path.of("../../api-specs/events/booking-cancelled.v1.example.json");
    private static final Path DISRUPTED = Path.of("../../api-specs/events/booking-disrupted.v1.example.json");

    @Test
    void should_read_the_cancellation_event_with_its_refund() throws Exception {
        BookingCancelledEvent event = EventParser.parse(Files.readString(CANCELLED), BookingCancelledEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("7d2c1a90-5b1e-4c55-8a3a-2f9a6f1c0a22"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.contactEmail()).isEqualTo("priya@example.com");
        assertThat(event.segments()).containsExactly(new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)));
        assertThat(event.refund().decision()).isEqualTo("FULL_REFUND");
        assertThat(event.refund().amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(event.refund().currency()).isEqualTo("USD");
    }

    @Test
    void should_read_a_cancellation_of_a_booking_that_was_never_paid() throws Exception {
        String withoutRefund = Files.readString(CANCELLED).replaceAll("(?s),\\s*\"refund\"\\s*:\\s*\\{.*?\\}", "");

        assertThat(EventParser.parse(withoutRefund, BookingCancelledEvent.class).refund()).isNull();
    }

    @Test
    void should_read_the_disruption_event() throws Exception {
        BookingDisruptedEvent event = EventParser.parse(Files.readString(DISRUPTED), BookingDisruptedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("c4e1b6d2-91a7-4f3e-b0d5-5a8e2c7f9b33"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.flightNumber()).isEqualTo("SK501");
        assertThat(event.departureDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(event.disruption()).isEqualTo("DELAYED");
        assertThat(event.revisedDeparture()).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
        assertThat(event.reason()).isEqualTo("Late inbound aircraft");
    }

    @Test
    void should_read_a_flight_cancellation_which_has_no_revised_time() throws Exception {
        String cancelled = Files.readString(DISRUPTED)
                .replace("\"DELAYED\"", "\"CANCELLED\"")
                .replaceAll("\\s*\"revisedDeparture\"[^,]*,", "");

        BookingDisruptedEvent event = EventParser.parse(cancelled, BookingDisruptedEvent.class);

        assertThat(event.disruption()).isEqualTo("CANCELLED");
        assertThat(event.revisedDeparture()).isNull();
    }

    @Test
    void should_refuse_an_event_of_the_wrong_kind_or_missing_what_the_email_needs() throws Exception {
        String disrupted = Files.readString(DISRUPTED);
        for (String broken : new String[] {
                disrupted.replace("\"booking.disrupted\"", "\"booking.cancelled\""),
                disrupted.replace("\"DELAYED\"", "\"EXPLODED\""),
                disrupted.replaceAll("\\s*\"revisedDeparture\"[^,]*,", ""), // a delay with no new time
                disrupted.replace("\"flightNumber\": \"SK501\",", ""),
                disrupted.replace("\"contactEmail\": \"priya@example.com\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, BookingDisruptedEvent.class));
        }
        String cancelled = Files.readString(CANCELLED);
        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> EventParser.parse(cancelled.replace("FULL_REFUND", "MAYBE"), BookingCancelledEvent.class));
    }

    @Test
    void should_never_print_the_address() throws Exception {
        assertThat(EventParser.parse(Files.readString(CANCELLED), BookingCancelledEvent.class).toString())
                .doesNotContain("priya@example.com");
        assertThat(EventParser.parse(Files.readString(DISRUPTED), BookingDisruptedEvent.class).toString())
                .doesNotContain("priya@example.com");
    }
}
