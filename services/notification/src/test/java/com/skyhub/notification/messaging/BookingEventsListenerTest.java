package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skyhub.notification.application.NotificationService;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.InvalidEventException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BookingEventsListenerTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");

    private final NotificationService service = mock(NotificationService.class);
    private final BookingEventsListener listener = new BookingEventsListener(service);

    @Test
    void should_hand_a_booking_confirmed_event_to_the_service() throws Exception {
        listener.onMessage(Files.readString(EXAMPLE));

        ArgumentCaptor<BookingConfirmedEvent> event = ArgumentCaptor.forClass(BookingConfirmedEvent.class);
        verify(service).bookingConfirmed(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().locator()).isEqualTo("ABC234");
    }

    @Test
    void should_hand_a_cancellation_to_the_service() throws Exception {
        listener.onMessage(Files.readString(Path.of("../../api-specs/events/booking-cancelled.v1.example.json")));

        ArgumentCaptor<BookingCancelledEvent> event = ArgumentCaptor.forClass(BookingCancelledEvent.class);
        verify(service).bookingCancelled(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().locator()).isEqualTo("ABC234");
    }

    @Test
    void should_hand_a_rebooking_to_the_service() throws Exception {
        listener.onMessage(Files.readString(Path.of("../../api-specs/events/booking-rebooked.v1.example.json")));

        ArgumentCaptor<BookingRebookedEvent> event = ArgumentCaptor.forClass(BookingRebookedEvent.class);
        verify(service).bookingRebooked(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().to().departureDate().toString()).isEqualTo("2026-11-03");
    }

    @Test
    void should_hand_a_disruption_to_the_service() throws Exception {
        listener.onMessage(Files.readString(Path.of("../../api-specs/events/booking-disrupted.v1.example.json")));

        ArgumentCaptor<BookingDisruptedEvent> event = ArgumentCaptor.forClass(BookingDisruptedEvent.class);
        verify(service).bookingDisrupted(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().flightNumber()).isEqualTo("SK501");
    }

    @Test
    void should_ignore_event_types_it_does_not_know_so_the_topic_can_grow() {
        listener.onMessage("{\"type\":\"seat.changed\",\"eventId\":\"0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11\"}");

        verify(service, never()).bookingConfirmed(any());
    }

    @Test
    void should_refuse_a_message_that_is_not_an_event_instead_of_dropping_it_silently() {
        assertThatExceptionOfType(InvalidEventException.class).isThrownBy(() -> listener.onMessage("not json"));
        verify(service, never()).bookingConfirmed(any());
    }
}
