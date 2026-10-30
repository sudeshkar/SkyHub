package com.skyhub.notification.application;

import com.skyhub.notification.domain.BookingConfirmedEvent;
import java.util.stream.Collectors;

/** Writes the emails. Plain text, no tracking, and nothing the traveller could not already see on their booking. */
public final class EmailComposer {

    private EmailComposer() {
    }

    public static EmailMessage bookingConfirmed(BookingConfirmedEvent event) {
        String flights = event.segments().stream()
                .map(s -> "  " + s.flightNumber() + " on " + s.departureDate())
                .collect(Collectors.joining("\n"));
        String passengers = event.passengers().stream()
                .map(p -> "  " + p.firstName() + " " + p.lastName())
                .collect(Collectors.joining("\n"));
        String body = """
                Your booking is confirmed.

                Booking reference: %s

                Flights (all times UTC on the booking page):
                %s

                Passengers:
                %s

                Total paid: %s %s

                To see or change your booking, open Manage booking on SkyHub and enter this reference
                with the last name of any passenger. You can check in online from 24 hours before departure.
                """.formatted(event.locator(), flights, passengers, event.totalAmount().toPlainString(), event.currency());
        return new EmailMessage(event.contactEmail(), "Your SkyHub booking " + event.locator() + " is confirmed", body);
    }
}
