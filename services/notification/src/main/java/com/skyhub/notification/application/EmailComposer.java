package com.skyhub.notification.application;

import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * Writes the emails. Plain text, no tracking, and nothing the traveller could not already see on their booking.
 * What an email promises about money matches the fare rules in the booking service (ADR-0016): a cancelled flight is
 * always refunded in full, a delayed one follows the normal 24-hour rule from the new departure time.
 */
public final class EmailComposer {

    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

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

    public static EmailMessage bookingCancelled(BookingCancelledEvent event) {
        String flights = event.segments().stream()
                .map(s -> "  " + s.flightNumber() + " on " + s.departureDate())
                .collect(Collectors.joining("\n"));
        String money = "";
        if (event.refund() != null) {
            money = "\n" + switch (event.refund().decision()) {
                case "FULL_REFUND" -> "We are refunding " + event.refund().amount().toPlainString() + " " + event.refund().currency()
                        + " to your original payment method. It can take a few days to appear on your statement.\n";
                default -> "You cancelled less than 24 hours before the first flight, so this booking is not refundable:"
                        + " nothing will be refunded.\n";
            };
        }
        String body = """
                Your booking %s has been cancelled and your seats have been released.

                Flights:
                %s
                %s
                If you did not cancel this booking, please contact us straight away.
                """.formatted(event.locator(), flights, money);
        return new EmailMessage(event.contactEmail(), "Your SkyHub booking " + event.locator() + " is cancelled", body);
    }

    public static EmailMessage bookingRebooked(BookingRebookedEvent event) {
        String seatList = event.passengers().stream()
                .map(p -> "  " + p.firstName() + " " + p.lastName() + ": " + p.seat())
                .collect(Collectors.joining("\n"));
        String oldFlight = event.from().flightNumber() + " on " + event.from().departureDate();
        String newFlight = event.to().flightNumber() + " on " + event.to().departureDate();
        String body = """
                Your booking %s has been moved to a new flight, because your original flight, %s, was disrupted.

                New flight: %s

                Your seats:
                %s

                If the new flight does not suit you, open Manage booking on SkyHub with your reference and the last name of
                any passenger: you can change your seats there, or cancel the booking.
                """.formatted(event.locator(), oldFlight, newFlight, seatList);
        return new EmailMessage(event.contactEmail(), "Your SkyHub booking " + event.locator() + " has moved to " + newFlight, body);
    }

    public static EmailMessage bookingDisrupted(BookingDisruptedEvent event) {
        boolean delayed = "DELAYED".equals(event.disruption());
        String flight = event.flightNumber() + " on " + event.departureDate();
        String reason = event.reason() == null || event.reason().isBlank() ? "" : "Reason: " + event.reason() + "\n\n";
        String body;
        if (delayed) {
            body = """
                    Your flight %s has been delayed.

                    New departure time: %s
                    %s
                    Your booking %s and your seats are unchanged. Online check-in now opens 24 hours before the new
                    departure time. If the new time does not suit you, you can cancel on Manage booking: you get a full
                    refund if you cancel at least 24 hours before the new departure time.
                    """.formatted(flight, UTC_TIME.format(event.revisedDeparture()), "\n" + reason, event.locator());
        } else {
            body = """
                    We are sorry: your flight %s has been cancelled.

                    %sYou can cancel booking %s on Manage booking at any time for a full refund, then search for another
                    flight. A flight we cancel is always refunded in full.
                    """.formatted(flight, reason, event.locator());
        }
        return new EmailMessage(event.contactEmail(),
                "Your SkyHub flight " + flight + " is " + (delayed ? "delayed" : "cancelled"), body);
    }
}
