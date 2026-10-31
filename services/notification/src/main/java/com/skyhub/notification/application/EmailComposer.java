package com.skyhub.notification.application;

import com.skyhub.notification.domain.AccountEmailChangeLinkEvent;
import com.skyhub.notification.domain.AccountEmailChangedEvent;
import com.skyhub.notification.domain.AccountPasswordLinkEvent;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingCheckInOpenEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingDisruptionWithdrawnEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.BookingRefundCompletedEvent;
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

    /**
     * The link carries the token in the query string, encoded, to the page where the person chooses a password. {@code token} is the
     * opened one (ADR-0046): the event itself only ever holds it sealed.
     */
    public static EmailMessage passwordLink(AccountPasswordLinkEvent event, String token, String siteUrl) {
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        String link = base + "/account/set-password?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        String until = UTC_TIME.format(event.expiresAt());
        if ("RESET".equals(event.purpose())) {
            return new EmailMessage(event.contactEmail(), "Reset your SkyHub password", """
                    Someone asked to reset the password of your SkyHub account. To choose a new one, open this link:

                    %s

                    The link works once and stops working at %s. Until you use it, your current password stays as it is.

                    If you did not ask for this, ignore this email: nothing changes.
                    """.formatted(link, until));
        }
        return new EmailMessage(event.contactEmail(), "Finish creating your SkyHub account", """
                Someone asked to create a SkyHub account with this email address. To finish, choose a password by opening this link:

                %s

                The link works once and stops working at %s.

                If you did not ask for this, ignore this email: no account will be usable.
                """.formatted(link, until));
    }

    /**
     * Goes to the NEW address, whose owner proves it can be read by using the link while signed in to the account (ADR-0039). It
     * says what it will do, and that nothing changes unless the link is used.
     */
    public static EmailMessage emailChangeLink(AccountEmailChangeLinkEvent event, String token, String siteUrl) {
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        String link = base + "/account/confirm-email?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        return new EmailMessage(event.contactEmail(), "Confirm your new SkyHub email address", """
                Someone signed in to a SkyHub account asked to change its email address to this one. To confirm, sign in to that
                account and then open this link:

                %s

                The link works once and stops working at %s. After it is used, bookings made with this address will appear in the
                account, and you will sign in with this address from then on.

                If you did not ask for this, ignore this email: nothing changes.
                """.formatted(link, UTC_TIME.format(event.expiresAt())));
    }

    /**
     * Goes to the OLD address when an account moves (ADR-0040). It names the new address only masked. With {@code undoToken} (ADR-0054)
     * it holds a link that takes the account back; that link can only undo this move, never make one. A notice made before the link
     * existed passes no token and holds no link.
     */
    public static EmailMessage emailChanged(AccountEmailChangedEvent event, String undoToken, String siteUrl) {
        String undo = undoToken == null ? "" : """

                If you did not make this change, take your account back by opening this link:

                %s

                It works once and stops working at %s. It puts your old address back on the account, signs everyone out and
                removes the password, so whoever made the change can no longer use it. We then send you a link to
                choose a new password. Bookings made with this address will be listed in the account again.
                """.formatted(undoLink(undoToken, siteUrl), UTC_TIME.format(event.undoExpiresAt()));
        return new EmailMessage(event.contactEmail(), "The email address of your SkyHub account was changed", """
                The email address of the SkyHub account that used this address was changed to %s at %s.

                From now on that account is signed in to with the new address, and bookings made with the new address are listed in it.

                If you made this change, there is nothing you need to do.
                %s
                If you did not make this change, someone who knew your password and could read the new mailbox did. Tell SkyHub
                support straight away.
                """.formatted(event.newEmailMasked(), UTC_TIME.format(event.occurredAt()), undo));
    }

    private static String undoLink(String token, String siteUrl) {
        String base = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        return base + "/account/undo-email-change?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static EmailMessage refundCompleted(BookingRefundCompletedEvent event) {
        return new EmailMessage(event.contactEmail(), "Your SkyHub refund for booking " + event.locator() + " has been paid", """
                Your refund for booking %s has now been paid: %s %s has been sent back to your original payment method.
                It can take a few days to appear on your statement.

                We are sorry it took longer than it should. You do not need to do anything.
                """.formatted(event.locator(), event.amount().toPlainString(), event.currency()));
    }

    public static EmailMessage checkInOpen(BookingCheckInOpenEvent event) {
        String flight = event.flightNumber() + " on " + event.departureDate();
        return new EmailMessage(event.contactEmail(), "Check-in is open for your SkyHub flight " + flight, """
                Online check-in is now open for your flight %s.

                Departure: %s
                Check-in closes: %s

                To check in, open Manage booking and enter your booking reference %s and your last name. Everyone on the
                booking checks in together, and you get your boarding passes straight away.
                """.formatted(flight, UTC_TIME.format(event.departure()), UTC_TIME.format(event.checkInCloses()), event.locator()));
    }

    public static EmailMessage bookingDisruptionWithdrawn(BookingDisruptionWithdrawnEvent event) {
        boolean wasDelay = "DELAYED".equals(event.disruption());
        String flight = event.flightNumber() + " on " + event.departureDate();
        if (wasDelay) {
            return new EmailMessage(event.contactEmail(), "Your SkyHub flight " + flight + " is back on time", """
                    Good news: the delay to your flight %s has been withdrawn.
                    The flight leaves at its original departure time again.

                    Your booking %s and your seats are unchanged.
                    Online check-in opens 24 hours before the original departure time.
                    """.formatted(flight, event.locator()));
        }
        return new EmailMessage(event.contactEmail(), "Good news: your SkyHub flight " + flight + " is operating", """
                Good news: your flight %s is no longer cancelled. It is operating as scheduled.

                Your booking %s and your seats are unchanged and you do not need to do anything.
                If you have already cancelled the booking, it stays cancelled and your refund is not affected.
                """.formatted(flight, event.locator()));
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
