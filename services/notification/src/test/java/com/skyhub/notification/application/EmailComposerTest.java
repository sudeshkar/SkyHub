package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.notification.domain.BookingConfirmedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailComposerTest {

    private static BookingConfirmedEvent event(List<BookingConfirmedEvent.Segment> segments) {
        return new BookingConfirmedEvent(UUID.randomUUID(), "booking.confirmed", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "ABC234", "priya@example.com",
                List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera"),
                        new BookingConfirmedEvent.Passenger("Anil", "Perera")),
                segments, new BigDecimal("200.00"), "USD");
    }

    @Test
    void should_put_the_reference_in_the_subject_and_send_to_the_booking_contact() {
        EmailMessage message = EmailComposer.bookingConfirmed(event(List.of(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)))));

        assertThat(message.to()).isEqualTo("priya@example.com");
        assertThat(message.subject()).isEqualTo("Your SkyHub booking ABC234 is confirmed");
    }

    @Test
    void should_list_every_flight_every_passenger_and_the_total_in_the_body() {
        EmailMessage message = EmailComposer.bookingConfirmed(event(List.of(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)),
                new BookingConfirmedEvent.Segment("SK901", LocalDate.of(2026, 11, 3)))));

        assertThat(message.body())
                .contains("ABC234")
                .contains("SK501 on 2026-11-02")
                .contains("SK901 on 2026-11-03")
                .contains("Priya Perera")
                .contains("Anil Perera")
                .contains("200.00 USD")
                .contains("last name");
    }

    @Test
    void should_never_print_the_address_in_a_message_summary() {
        EmailMessage message = EmailComposer.bookingConfirmed(event(List.of(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)))));

        assertThat(message.toString()).doesNotContain("priya@example.com").contains("p****@example.com");
    }
}
