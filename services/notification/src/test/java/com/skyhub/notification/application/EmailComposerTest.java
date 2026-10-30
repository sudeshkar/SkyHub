package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
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

    private static final List<BookingConfirmedEvent.Segment> FLIGHT = List.of(
            new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)));

    private static BookingCancelledEvent cancelled(BookingCancelledEvent.Refund refund) {
        return new BookingCancelledEvent(UUID.randomUUID(), "booking.cancelled", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "ABC234", "priya@example.com", FLIGHT, refund);
    }

    private static BookingDisruptedEvent disrupted(String kind, Instant revised, String reason) {
        return new BookingDisruptedEvent(UUID.randomUUID(), "booking.disrupted", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "ABC234", "priya@example.com", "SK501", LocalDate.of(2026, 11, 2), kind, revised, reason);
    }

    @Test
    void should_say_the_money_is_on_its_way_without_claiming_it_has_arrived() {
        EmailMessage message = EmailComposer.bookingCancelled(
                cancelled(new BookingCancelledEvent.Refund("FULL_REFUND", new BigDecimal("100.00"), "USD")));

        assertThat(message.subject()).isEqualTo("Your SkyHub booking ABC234 is cancelled");
        assertThat(message.body()).contains("SK501 on 2026-11-02").contains("refunding 100.00 USD")
                .contains("original payment method");
        assertThat(message.body()).doesNotContain("has been refunded");
    }

    @Test
    void should_say_plainly_that_a_late_cancellation_gets_nothing_back() {
        EmailMessage message = EmailComposer.bookingCancelled(
                cancelled(new BookingCancelledEvent.Refund("NO_REFUND", new BigDecimal("100.00"), "USD")));

        assertThat(message.body()).contains("24 hours").contains("nothing will be refunded");
    }

    @Test
    void should_not_mention_money_for_a_booking_that_was_never_paid() {
        EmailMessage message = EmailComposer.bookingCancelled(cancelled(null));

        assertThat(message.body()).contains("cancelled").doesNotContain("refund");
    }

    @Test
    void should_give_the_new_time_in_utc_and_the_reason_for_a_delay() {
        EmailMessage message = EmailComposer.bookingDisrupted(
                disrupted("DELAYED", Instant.parse("2026-11-02T14:30:00Z"), "Late inbound aircraft"));

        assertThat(message.subject()).isEqualTo("Your SkyHub flight SK501 on 2026-11-02 is delayed");
        assertThat(message.body()).contains("2026-11-02 14:30 UTC").contains("Late inbound aircraft")
                .contains("24 hours before the new departure time");
    }

    @Test
    void should_leave_out_the_reason_line_when_there_is_none() {
        EmailMessage message = EmailComposer.bookingDisrupted(disrupted("DELAYED", Instant.parse("2026-11-02T14:30:00Z"), ""));

        assertThat(message.body()).doesNotContain("Reason");
    }

    @Test
    void should_tell_the_traveller_a_cancelled_flight_is_always_refunded_in_full() {
        EmailMessage message = EmailComposer.bookingDisrupted(disrupted("CANCELLED", null, "Aircraft grounded"));

        assertThat(message.subject()).isEqualTo("Your SkyHub flight SK501 on 2026-11-02 is cancelled");
        assertThat(message.body()).contains("full refund").contains("Manage booking").contains("Aircraft grounded");
    }

    @Test
    void should_never_print_the_address_in_a_message_summary() {
        EmailMessage message = EmailComposer.bookingConfirmed(event(List.of(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)))));

        assertThat(message.toString()).doesNotContain("priya@example.com").contains("p****@example.com");
    }
}
