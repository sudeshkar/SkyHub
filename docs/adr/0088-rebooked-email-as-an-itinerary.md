# ADR-0088: The "your booking has moved" email as an itinerary card

**Status:** Accepted
**Date:** 2026-11-14

## Context

ADR-0087 made the booking confirmation an itinerary: a card per flight with the route in local time, the airports and the seats. The email a traveller gets when staff move their booking after a disruption (ADR-0023, ADR-0070) still said only "New flight: SK001 on 2026-11-19". It is the email that matters most on a bad day, and the one a demo of the disruption agent ends on. The `booking.rebooked` event carried only flight numbers and dates.

The booking service already looks up both flights in the flight catalog during a move: the rebooking rules need the route and times of the flight left and the flight joined (ADR-0036).

## Options considered

1. **The notification service looks the new flight up in flight-search.** Rejected for the same reason as in ADR-0087: the email should describe the flight as it was when the move was made, and the booking service already has that.
2. **The event's `from` and `to` carry the route and times, in the same shape as a confirmed booking's segments.** Chosen: no extra calls, and the notification service already reads both as the confirmation's segment type, so the new fields arrive with no parser change.

## Decision

- **The contract grows, still version 1** (`booking-rebooked.v1.example.json` and the checked-out example): `from` and `to` each add `origin`, `destination`, `departure` and `arrival`, as a confirmed booking's segments do (ADR-0087). A field the catalog did not know is left out.
- **`RebookingService` passes the two flight answers it already used for the rules** to the event. Nothing new is looked up.
- **The email shows the new flight as an itinerary card** headed "Your new flight, SK001 · Fri 20 Nov 2026", with the route drawn large, the airports and each passenger's new seat, the old flight named the same way, and "Times are local at each airport". The check-in-again note for a party the move checked out (ADR-0070) stays. `Itinerary.of(segment, seats)` builds the card from one segment, so the confirmation and this email describe flights the same way.
- An event from before this change (no routes) keeps the old email. The subject does not change, because tests and tools look for it.

## Consequences

- Positive: the move email looks like the confirmation and tells the traveller when and where the new flight leaves, in their local time.
- Positive: no new calls and no new parser; the shared shape keeps the two events alike.
- Negative: the move email still carries no new e-ticket PDF. The traveller has the new flight in the email and on Manage booking; a PDF for a changed booking is the follow-up ADR-0087 already names.

## Verification

- Booking service: the contract tests write both rebooked examples field for field, and a new test checks the move passes the catalog's own answers for both flights.
- Notification service: the examples and an event from before this change are read; the email shows the itinerary card (local times, airports, seat, the old flight's day) and keeps the check-in-again note for a party that was checked out.
- **End to end in the running stack:** with the booking and notification services rebuilt from this branch, a booking on SK001 was moved by cancelling its flight in `/ops`, getting the Groq planner's proposal and approving it. The email that arrived in Mailpit showed the new card (DXB 12:30 to LHR 16:20, seat 10A). Mailpit's HTML check scored it 90%.
- `./mvnw clean verify`: booking 1057 tests and notification 212 tests, no failures.
