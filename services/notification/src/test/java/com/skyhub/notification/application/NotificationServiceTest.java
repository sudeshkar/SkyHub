package com.skyhub.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.SentNotification;
import com.skyhub.notification.persistence.SentNotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:05Z");
    private static final UUID EVENT_ID = UUID.fromString("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11");

    @Mock
    private SentNotificationRepository sent;

    @Mock
    private EmailSender email;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private NotificationService service;
    private BookingConfirmedEvent event;

    @BeforeEach
    void setUp() {
        service = new NotificationService(sent, email, Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction(), new NotificationMetrics(registry));
        event = new BookingConfirmedEvent(EVENT_ID, "booking.confirmed", 1, Instant.parse("2026-10-30T10:00:00Z"),
                "ABC234", "priya@example.com", List.of(new BookingConfirmedEvent.Passenger("Priya", "Perera")),
                List.of(new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2))),
                new BigDecimal("100.00"), "USD");
    }

    private double count(String outcome) {
        return registry.get("skyhub.notifications").tag("type", "booking.confirmed").tag("outcome", outcome).counter().count();
    }

    @Test
    void should_send_the_confirmation_and_record_that_it_was_sent() {
        service.bookingConfirmed(event);

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(message.capture());
        assertThat(message.getValue().to()).isEqualTo("priya@example.com");
        assertThat(message.getValue().subject()).contains("ABC234");

        ArgumentCaptor<SentNotification> row = ArgumentCaptor.forClass(SentNotification.class);
        verify(sent).saveAndFlush(row.capture());
        assertThat(row.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(row.getValue().getLocator()).isEqualTo("ABC234");
        assertThat(row.getValue().getRecipient()).isEqualTo("p****@example.com"); // never the real address
        assertThat(row.getValue().getSentAt()).isEqualTo(NOW);
        assertThat(count("sent")).isEqualTo(1);
    }

    private double count(String type, String outcome) {
        return registry.get("skyhub.notifications").tag("type", type).tag("outcome", outcome).counter().count();
    }

    private BookingCancelledEvent cancelled() {
        return new BookingCancelledEvent(UUID.fromString("7d2c1a90-5b1e-4c55-8a3a-2f9a6f1c0a22"), "booking.cancelled", 1,
                Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com", event.segments(),
                new BookingCancelledEvent.Refund("FULL_REFUND", new BigDecimal("100.00"), "USD"));
    }

    private BookingDisruptedEvent disrupted() {
        return new BookingDisruptedEvent(UUID.fromString("c4e1b6d2-91a7-4f3e-b0d5-5a8e2c7f9b33"), "booking.disrupted", 1,
                Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com", "SK501", LocalDate.of(2026, 11, 2),
                "DELAYED", Instant.parse("2026-11-02T14:30:00Z"), "Late inbound aircraft");
    }

    @Test
    void should_send_and_record_a_cancellation_email_once() {
        service.bookingCancelled(cancelled());

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(message.capture());
        assertThat(message.getValue().subject()).isEqualTo("Your SkyHub booking ABC234 is cancelled");
        ArgumentCaptor<SentNotification> row = ArgumentCaptor.forClass(SentNotification.class);
        verify(sent).saveAndFlush(row.capture());
        assertThat(row.getValue().getType()).isEqualTo("booking.cancelled");
        assertThat(count("booking.cancelled", "sent")).isEqualTo(1);
    }

    @Test
    void should_send_and_record_a_disruption_email_once() {
        service.bookingDisrupted(disrupted());

        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(email).send(message.capture());
        assertThat(message.getValue().subject()).contains("SK501").contains("delayed");
        ArgumentCaptor<SentNotification> row = ArgumentCaptor.forClass(SentNotification.class);
        verify(sent).saveAndFlush(row.capture());
        assertThat(row.getValue().getType()).isEqualTo("booking.disrupted");
        assertThat(count("booking.disrupted", "sent")).isEqualTo(1);
    }

    @Test
    void should_send_and_record_a_rebooking_email_once_and_skip_a_redelivery() {
        BookingRebookedEvent moved = new BookingRebookedEvent(UUID.fromString("e5a3d7c1-2f48-4b6a-9c10-7d1e8f3b4a55"),
                "booking.rebooked", 1, Instant.parse("2026-10-30T10:00:00Z"), "ABC234", "priya@example.com",
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 2)),
                new BookingConfirmedEvent.Segment("SK501", LocalDate.of(2026, 11, 3)),
                List.of(new BookingRebookedEvent.SeatedPassenger("Priya", "Perera", "14A")));

        service.bookingRebooked(moved);
        when(sent.existsByEventId(moved.eventId())).thenReturn(true);
        service.bookingRebooked(moved);

        verify(email, org.mockito.Mockito.times(1)).send(any(EmailMessage.class));
        assertThat(count("booking.rebooked", "sent")).isEqualTo(1);
        assertThat(count("booking.rebooked", "duplicate")).isEqualTo(1);
    }

    @Test
    void should_skip_a_redelivered_cancellation_or_disruption() {
        when(sent.existsByEventId(cancelled().eventId())).thenReturn(true);
        when(sent.existsByEventId(disrupted().eventId())).thenReturn(true);

        service.bookingCancelled(cancelled());
        service.bookingDisrupted(disrupted());

        verify(email, never()).send(any(EmailMessage.class));
        assertThat(count("booking.cancelled", "duplicate")).isEqualTo(1);
        assertThat(count("booking.disrupted", "duplicate")).isEqualTo(1);
    }

    @Test
    void should_claim_the_event_before_sending_so_a_concurrent_duplicate_cannot_send_twice() {
        service.bookingConfirmed(event);

        InOrder order = inOrder(sent, email);
        order.verify(sent).saveAndFlush(any(SentNotification.class));
        order.verify(email).send(any(EmailMessage.class));
    }

    @Test
    void should_skip_an_event_it_has_already_handled_because_delivery_is_at_least_once() {
        when(sent.existsByEventId(EVENT_ID)).thenReturn(true);

        service.bookingConfirmed(event);

        verify(email, never()).send(any(EmailMessage.class));
        verify(sent, never()).saveAndFlush(any(SentNotification.class));
        assertThat(count("duplicate")).isEqualTo(1);
    }

    @Test
    void should_let_a_sending_failure_escape_so_the_claim_rolls_back_and_the_message_is_retried() {
        doThrow(new EmailUnavailableException("smtp down", null)).when(email).send(any(EmailMessage.class));

        assertThatExceptionOfType(EmailUnavailableException.class).isThrownBy(() -> service.bookingConfirmed(event));

        assertThat(count("failed")).isEqualTo(1);
    }
}
