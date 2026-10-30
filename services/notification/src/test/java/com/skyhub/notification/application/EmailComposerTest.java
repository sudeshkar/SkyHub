package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.notification.domain.AccountPasswordLinkEvent;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingCheckInOpenEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingDisruptionWithdrawnEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.BookingRefundCompletedEvent;
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

    private static AccountPasswordLinkEvent link(String purpose) {
        return new AccountPasswordLinkEvent(UUID.randomUUID(), "account.password-link", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "priya@example.com", purpose, "tok+en/with=chars", Instant.parse("2026-10-30T11:00:00Z"));
    }

    @Test
    void should_send_a_sign_up_link_that_carries_the_token_safely_and_says_when_it_stops_working() {
        EmailMessage message = EmailComposer.passwordLink(link("SIGN_UP"), "https://skyhub.example");

        assertThat(message.subject()).isEqualTo("Finish creating your SkyHub account");
        assertThat(message.body()).contains("https://skyhub.example/account/set-password?token=tok%2Ben%2Fwith%3Dchars")
                .contains("2026-10-30 11:00 UTC").contains("did not ask");
    }

    @Test
    void should_send_a_reset_link_that_says_the_old_password_works_until_a_new_one_is_chosen() {
        EmailMessage message = EmailComposer.passwordLink(link("RESET"), "https://skyhub.example/");

        assertThat(message.subject()).isEqualTo("Reset your SkyHub password");
        assertThat(message.body()).contains("https://skyhub.example/account/set-password?token=").contains("current password");
    }

    @Test
    void should_tell_the_traveller_a_delayed_refund_has_now_been_paid() {
        EmailMessage message = EmailComposer.refundCompleted(new BookingRefundCompletedEvent(UUID.randomUUID(),
                "booking.refund-completed", 1, Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com",
                new java.math.BigDecimal("100.00"), "USD"));

        assertThat(message.subject()).isEqualTo("Your SkyHub refund for booking ABC234 has been paid");
        assertThat(message.body()).contains("100.00 USD").contains("original payment method").contains("few days");
    }

    @Test
    void should_tell_the_traveller_check_in_is_open_when_it_closes_and_where_to_do_it() {
        EmailMessage message = EmailComposer.checkInOpen(new BookingCheckInOpenEvent(UUID.randomUUID(), "booking.check-in-open", 1,
                Instant.parse("2026-11-01T14:30:00Z"), "ABC234", "priya@example.com", "SK501", LocalDate.of(2026, 11, 2),
                Instant.parse("2026-11-02T14:30:00Z"), Instant.parse("2026-11-02T13:30:00Z")));

        assertThat(message.subject()).isEqualTo("Check-in is open for your SkyHub flight SK501 on 2026-11-02");
        assertThat(message.body()).contains("2026-11-02 14:30 UTC").contains("2026-11-02 13:30 UTC")
                .contains("Manage booking").contains("ABC234");
    }

    @Test
    void should_tell_the_traveller_a_cancellation_was_withdrawn_and_that_a_cancelled_booking_stays_cancelled() {
        EmailMessage message = EmailComposer.bookingDisruptionWithdrawn(withdrawn("CANCELLED"));

        assertThat(message.subject()).isEqualTo("Good news: your SkyHub flight SK501 on 2026-11-02 is operating");
        assertThat(message.body()).contains("no longer cancelled").contains("ABC234").contains("already cancelled");
    }

    @Test
    void should_tell_the_traveller_a_delay_was_withdrawn_and_the_original_time_applies() {
        EmailMessage message = EmailComposer.bookingDisruptionWithdrawn(withdrawn("DELAYED"));

        assertThat(message.subject()).isEqualTo("Your SkyHub flight SK501 on 2026-11-02 is back on time");
        assertThat(message.body()).contains("original departure time").contains("24 hours before");
    }

    private static BookingDisruptionWithdrawnEvent withdrawn(String disruption) {
        return new BookingDisruptionWithdrawnEvent(UUID.randomUUID(), "booking.disruption-withdrawn", 1,
                Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com", "SK501", LocalDate.of(2026, 11, 2), disruption);
    }

    @Test
    void should_tell_the_traveller_about_the_new_flight_and_every_passengers_seat() {
        EmailMessage message = EmailComposer.bookingRebooked(new BookingRebookedEvent(UUID.randomUUID(), "booking.rebooked", 1,
                Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com",
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)),
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 3)),
                List.of(new BookingRebookedEvent.SeatedPassenger("Priya", "Perera", "14A"),
                        new BookingRebookedEvent.SeatedPassenger("Anil", "Perera", "14B"))));

        assertThat(message.subject()).isEqualTo("Your SkyHub booking ABC234 has moved to SK501 on 2026-11-03");
        assertThat(message.body()).contains("SK501 on 2026-11-02").contains("SK501 on 2026-11-03")
                .contains("Priya Perera: 14A").contains("Anil Perera: 14B").contains("Manage booking");
    }

    @Test
    void should_make_no_promise_about_money_in_the_rebooking_email() {
        EmailMessage message = EmailComposer.bookingRebooked(new BookingRebookedEvent(UUID.randomUUID(), "booking.rebooked", 1,
                Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com",
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)),
                new BookingConfirmedEvent.Segment("SK502", LocalDate.of(2026, 11, 2)),
                List.of(new BookingRebookedEvent.SeatedPassenger("Priya", "Perera", "14A"))));

        assertThat(message.body()).doesNotContain("refund");   // what is refundable depends on why the flight was disrupted
    }

    @Test
    void should_never_print_the_address_in_a_message_summary() {
        EmailMessage message = EmailComposer.bookingConfirmed(event(List.of(
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)))));

        assertThat(message.toString()).doesNotContain("priya@example.com").contains("p****@example.com");
    }
}
