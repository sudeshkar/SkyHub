package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.RefundPolicy;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxBookingEventsTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");
    private static final Path CANCELLED_EXAMPLE = Path.of("../../api-specs/events/booking-cancelled.v1.example.json");
    private static final Path DISRUPTED_EXAMPLE = Path.of("../../api-specs/events/booking-disrupted.v1.example.json");
    private static final Path REBOOKED_EXAMPLE = Path.of("../../api-specs/events/booking-rebooked.v1.example.json");
    private static final UUID REBOOKED_ID = UUID.fromString("e5a3d7c1-2f48-4b6a-9c10-7d1e8f3b4a55");
    private static final UUID CANCELLED_ID = UUID.fromString("7d2c1a90-5b1e-4c55-8a3a-2f9a6f1c0a22");
    private static final UUID DISRUPTED_ID = UUID.fromString("c4e1b6d2-91a7-4f3e-b0d5-5a8e2c7f9b33");
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11");

    @Mock
    private OutboxEventRepository outbox;

    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final JsonMapper json = JsonMapper.builder().build();

    private OutboxBookingEvents events() {
        return events(EVENT_ID);
    }

    private OutboxBookingEvents events(UUID id) {
        return new OutboxBookingEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), () -> id);
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        return saved.getValue();
    }

    private static Pnr pnr() {
        return Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", LocalDate.of(2026, 11, 2))),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, "keyhash", "fingerprint");
    }

    private OutboxEvent recorded() {
        events().bookingConfirmed(pnr(), Money.of("100.00", "USD"));
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void should_write_the_event_the_notification_service_expects() throws Exception {
        OutboxEvent event = recorded();

        JsonNode expected = json.readTree(Files.readString(EXAMPLE));
        JsonNode actual = json.readTree(cipher.decrypt(event.getPayload()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void should_write_the_cancellation_event_with_the_refund_decision_and_amount() throws Exception {
        events(CANCELLED_ID).bookingCancelled(pnr(), RefundPolicy.Decision.FULL_REFUND, Optional.of(Money.of("100.00", "USD")));

        OutboxEvent event = saved();

        assertThat(event.getEventType()).isEqualTo("booking.cancelled");
        assertThat(event.getAggregateId()).isEqualTo("ABC234");
        assertThat(json.readTree(cipher.decrypt(event.getPayload()))).isEqualTo(json.readTree(Files.readString(CANCELLED_EXAMPLE)));
    }

    @Test
    void should_say_no_refund_and_leave_the_amount_out_for_a_booking_that_was_never_paid() throws Exception {
        events(CANCELLED_ID).bookingCancelled(pnr(), RefundPolicy.Decision.NO_REFUND, Optional.empty());

        JsonNode payload = json.readTree(cipher.decrypt(saved().getPayload()));

        assertThat(payload.has("refund")).isFalse();
    }

    @Test
    void should_report_no_refund_when_cancelled_too_late() throws Exception {
        events(CANCELLED_ID).bookingCancelled(pnr(), RefundPolicy.Decision.NO_REFUND, Optional.of(Money.of("100.00", "USD")));

        JsonNode payload = json.readTree(cipher.decrypt(saved().getPayload()));

        assertThat(payload.get("refund").get("decision").asString()).isEqualTo("NO_REFUND");
    }

    @Test
    void should_write_one_disruption_event_per_booking_as_the_notification_service_expects() throws Exception {
        events(DISRUPTED_ID).bookingDisrupted(pnr(), Disruption.delayed("SK501", LocalDate.of(2026, 11, 2),
                Instant.parse("2026-11-02T14:30:00Z"), "Late inbound aircraft", NOW));

        OutboxEvent event = saved();

        assertThat(event.getEventType()).isEqualTo("booking.disrupted");
        assertThat(event.getAggregateId()).isEqualTo("ABC234");
        assertThat(json.readTree(cipher.decrypt(event.getPayload()))).isEqualTo(json.readTree(Files.readString(DISRUPTED_EXAMPLE)));
    }

    @Test
    void should_write_the_rebooking_event_with_the_new_seats_as_the_notification_service_expects() throws Exception {
        PnrSegment next = new PnrSegment("SK501", LocalDate.of(2026, 11, 3));
        Pnr moved = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(next),
                List.of(new PnrPassenger("Priya", "Perera", List.of("14A"))), NOW, "keyhash", "fingerprint");

        events(REBOOKED_ID).bookingRebooked(moved, new PnrSegment("SK501", LocalDate.of(2026, 11, 2)), next);

        OutboxEvent event = saved();
        assertThat(event.getEventType()).isEqualTo("booking.rebooked");
        assertThat(event.getAggregateId()).isEqualTo("ABC234");
        assertThat(json.readTree(cipher.decrypt(event.getPayload()))).isEqualTo(json.readTree(Files.readString(REBOOKED_EXAMPLE)));
    }

    @Test
    void should_leave_the_revised_time_out_for_a_cancelled_flight() throws Exception {
        events(DISRUPTED_ID).bookingDisrupted(pnr(),
                Disruption.cancelled("SK501", LocalDate.of(2026, 11, 2), "Aircraft grounded", NOW));

        JsonNode payload = json.readTree(cipher.decrypt(saved().getPayload()));

        assertThat(payload.get("disruption").asString()).isEqualTo("CANCELLED");
        assertThat(payload.has("revisedDeparture")).isFalse();
    }

    @Test
    void should_key_the_event_by_booking_so_one_bookings_events_stay_in_order() {
        OutboxEvent event = recorded();

        assertThat(event.getId()).isEqualTo(EVENT_ID);
        assertThat(event.getAggregateId()).isEqualTo("ABC234");
        assertThat(event.getEventType()).isEqualTo("booking.confirmed");
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.isPublished()).isFalse();
    }

    @Test
    void should_never_keep_the_travellers_email_or_name_in_plain_text() {
        String stored = recorded().getPayload();

        assertThat(stored).doesNotContain("priya@example.com").doesNotContain("Perera").doesNotContain("ABC234");
    }
}
