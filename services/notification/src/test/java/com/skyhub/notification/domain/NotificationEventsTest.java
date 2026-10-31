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
    private static final Path REBOOKED = Path.of("../../api-specs/events/booking-rebooked.v1.example.json");
    private static final Path DISRUPTED = Path.of("../../api-specs/events/booking-disrupted.v1.example.json");
    private static final Path CHECK_IN_OPEN = Path.of("../../api-specs/events/booking-check-in-open.v1.example.json");
    private static final Path REFUND_COMPLETED = Path.of("../../api-specs/events/booking-refund-completed.v1.example.json");
    private static final Path PASSWORD_LINK = Path.of("../../api-specs/events/account-password-link.v1.example.json");
    private static final Path WITHDRAWN = Path.of("../../api-specs/events/booking-disruption-withdrawn.v1.example.json");

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
    void should_read_the_check_in_open_event_with_both_times() throws Exception {
        BookingCheckInOpenEvent event = EventParser.parse(Files.readString(CHECK_IN_OPEN), BookingCheckInOpenEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("a3b7c9d1-5e24-4f68-8a10-9c2d4e6f8b55"));
        assertThat(event.flightNumber()).isEqualTo("SK501");
        assertThat(event.departure()).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
        assertThat(event.checkInCloses()).isEqualTo(Instant.parse("2026-11-02T13:30:00Z"));
    }

    @Test
    void should_refuse_a_check_in_event_missing_what_the_email_needs() throws Exception {
        String open = Files.readString(CHECK_IN_OPEN);
        for (String broken : new String[] {
                open.replace("booking.check-in-open", "booking.disrupted"),
                open.replaceAll("\\s*\"departure\": \"[^\"]*\",", ""),
                open.replaceAll(",\\s*\"checkInCloses\": \"[^\"]*\"", ""),
                open.replace("\"flightNumber\": \"SK501\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, BookingCheckInOpenEvent.class));
        }
    }

    @Test
    void should_read_the_password_link_event_which_belongs_to_no_booking() throws Exception {
        AccountPasswordLinkEvent event = EventParser.parse(Files.readString(PASSWORD_LINK), AccountPasswordLinkEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("2b6d8f10-4c93-4a57-9e21-8f0a1c3d5e77"));
        assertThat(event.contactEmail()).isEqualTo("priya@example.com");
        assertThat(event.purpose()).isEqualTo("SIGN_UP");
        assertThat(event.token()).startsWith("dGVzdC10b2tlbi");
        assertThat(event.expiresAt()).isEqualTo(Instant.parse("2026-10-30T11:00:00Z"));
        assertThat(event.locator()).isNull();
    }

    @Test
    void should_refuse_a_password_link_event_without_a_token_a_known_purpose_or_an_address() throws Exception {
        String link = Files.readString(PASSWORD_LINK);
        for (String broken : new String[] {
                link.replace("account.password-link", "booking.cancelled"),
                link.replace("SIGN_UP", "SOMETHING_ELSE"),
                link.replaceAll("\\s*\"token\": \"[^\"]*\",", ""),
                link.replaceAll("\\s*\"expiresAt\": \"[^\"]*\"", "").replace("\"token\"", "\"x\""),
                link.replace("\"contactEmail\": \"priya@example.com\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, AccountPasswordLinkEvent.class));
        }
    }

    @Test
    void should_never_print_the_token_or_the_address() throws Exception {
        AccountPasswordLinkEvent event = EventParser.parse(Files.readString(PASSWORD_LINK), AccountPasswordLinkEvent.class);

        assertThat(event.toString()).doesNotContain("priya@example.com").doesNotContain(event.token());
    }

    @Test
    void should_read_the_refund_completed_event_with_its_amount() throws Exception {
        BookingRefundCompletedEvent event = EventParser.parse(Files.readString(REFUND_COMPLETED), BookingRefundCompletedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("f1a9c3e7-6b25-4d80-9e14-2a7c5b8d0f66"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(event.currency()).isEqualTo("USD");
    }

    @Test
    void should_refuse_a_refund_event_without_a_positive_amount_or_a_currency() throws Exception {
        String completed = Files.readString(REFUND_COMPLETED);
        for (String broken : new String[] {
                completed.replace("booking.refund-completed", "booking.cancelled"),
                completed.replace("100.00", "0"),
                completed.replace("100.00", "-5"),
                completed.replace("\"currency\": \"USD\"", "\"currency\": \"\""),
                completed.replaceAll(",\\s*\"amount\": 100.00", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, BookingRefundCompletedEvent.class));
        }
    }

    @Test
    void should_read_the_event_that_a_disruption_was_withdrawn() throws Exception {
        BookingDisruptionWithdrawnEvent event = EventParser.parse(Files.readString(WITHDRAWN), BookingDisruptionWithdrawnEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("d8f2a4b6-17c3-4e90-b5a1-3c6e9d0f2b44"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.flightNumber()).isEqualTo("SK501");
        assertThat(event.departureDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(event.disruption()).isEqualTo("CANCELLED");
    }

    @Test
    void should_refuse_a_withdrawal_event_of_the_wrong_kind_or_missing_the_flight() throws Exception {
        String withdrawn = Files.readString(WITHDRAWN);
        for (String broken : new String[] {
                withdrawn.replace("booking.disruption-withdrawn", "booking.disrupted"),
                withdrawn.replace("\"CANCELLED\"", "\"EXPLODED\""),
                withdrawn.replace("\"flightNumber\": \"SK501\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, BookingDisruptionWithdrawnEvent.class));
        }
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
    void should_read_the_rebooking_event_with_each_passengers_new_seat() throws Exception {
        BookingRebookedEvent event = EventParser.parse(Files.readString(REBOOKED), BookingRebookedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("e5a3d7c1-2f48-4b6a-9c10-7d1e8f3b4a55"));
        assertThat(event.locator()).isEqualTo("ABC234");
        assertThat(event.from()).isEqualTo(new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)));
        assertThat(event.to()).isEqualTo(new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 3)));
        assertThat(event.passengers()).containsExactly(new BookingRebookedEvent.SeatedPassenger("Priya", "Perera", "14A"));
    }

    @Test
    void should_refuse_a_rebooking_event_without_a_new_flight_or_seats() throws Exception {
        String valid = Files.readString(REBOOKED);
        for (String broken : new String[] {
                valid.replaceAll("(?s)\"to\"\\s*:\\s*\\{.*?\\},", ""),
                valid.replace("\"booking.rebooked\"", "\"booking.cancelled\""),
                valid.replaceAll("(?s)\"passengers\".*?\\]", "\"passengers\": []")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, BookingRebookedEvent.class));
        }
    }

    @Test
    void should_never_print_the_address() throws Exception {
        assertThat(EventParser.parse(Files.readString(CANCELLED), BookingCancelledEvent.class).toString())
                .doesNotContain("priya@example.com");
        assertThat(EventParser.parse(Files.readString(DISRUPTED), BookingDisruptedEvent.class).toString())
                .doesNotContain("priya@example.com");
    }

    private static final Path EMAIL_CHANGE_LINK = Path.of("../../api-specs/events/account-email-change-link.v1.example.json");

    @Test
    void should_read_the_email_change_link_event_which_belongs_to_no_booking_and_goes_to_the_new_address() throws Exception {
        AccountEmailChangeLinkEvent event = EventParser.parse(Files.readString(EMAIL_CHANGE_LINK), AccountEmailChangeLinkEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("6c1e9a52-7d3b-4f08-b4a6-2e9f0d8c3a14"));
        assertThat(event.type()).isEqualTo("account.email-change-link");
        assertThat(event.contactEmail()).isEqualTo("priya.new@example.com");
        assertThat(event.token()).startsWith("bm90LWEtcmVhbC1v");
        assertThat(event.expiresAt()).isEqualTo(Instant.parse("2026-11-01T11:00:00Z"));
        assertThat(event.locator()).isNull();
    }

    @Test
    void should_refuse_an_email_change_link_event_without_a_token_an_expiry_the_right_type_or_an_address() throws Exception {
        String link = Files.readString(EMAIL_CHANGE_LINK);
        for (String broken : new String[] {
                link.replace("account.email-change-link", "account.password-link"),
                link.replaceAll("\\s*\"token\": \"[^\"]*\",", ""),
                link.replaceAll(",\\s*\"expiresAt\": \"[^\"]*\"", ""),
                link.replace("\"contactEmail\": \"priya.new@example.com\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, AccountEmailChangeLinkEvent.class));
        }
    }

    @Test
    void should_never_print_the_email_change_token_or_the_new_address() throws Exception {
        AccountEmailChangeLinkEvent event = EventParser.parse(Files.readString(EMAIL_CHANGE_LINK), AccountEmailChangeLinkEvent.class);

        assertThat(event.toString()).doesNotContain("priya.new@example.com").doesNotContain(event.token());
    }

    private static final Path EMAIL_CHANGED = Path.of("../../api-specs/events/account-email-changed.v1.example.json");

    @Test
    void should_read_the_email_changed_event_which_goes_to_the_old_address_and_names_the_new_one_only_masked() throws Exception {
        AccountEmailChangedEvent event = EventParser.parse(Files.readString(EMAIL_CHANGED), AccountEmailChangedEvent.class);

        assertThat(event.eventId()).isEqualTo(UUID.fromString("9d4f2b73-1a8e-4c60-b5d9-3e7a0c6f1b28"));
        assertThat(event.type()).isEqualTo("account.email-changed");
        assertThat(event.contactEmail()).isEqualTo("priya@example.com");
        assertThat(event.newEmailMasked()).isEqualTo("p****@example.com");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-01T10:30:00Z"));
        assertThat(event.locator()).isNull();
    }

    @Test
    void should_refuse_an_email_changed_event_without_the_right_type_a_masked_address_or_an_address_to_write_to() throws Exception {
        String notice = Files.readString(EMAIL_CHANGED);
        for (String broken : new String[] {
                notice.replace("account.email-changed", "account.email-change-link"),
                notice.replaceAll(",\\s*\"newEmailMasked\": \"[^\"]*\"", ""),
                notice.replace("\"contactEmail\": \"priya@example.com\",", "")}) {
            assertThatExceptionOfType(InvalidEventException.class).as(broken)
                    .isThrownBy(() -> EventParser.parse(broken, AccountEmailChangedEvent.class));
        }
    }

    @Test
    void should_never_print_the_old_address() throws Exception {
        AccountEmailChangedEvent event = EventParser.parse(Files.readString(EMAIL_CHANGED), AccountEmailChangedEvent.class);

        assertThat(event.toString()).doesNotContain("priya@example.com");
    }
}
