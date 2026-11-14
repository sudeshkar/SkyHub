# ADR-0087: The confirmation as an e-ticket receipt, with a PDF

**Status:** Accepted
**Date:** 2026-11-14

## Context

The booking confirmation (ADR-0019, designed in ADR-0086) lists each flight as "SK501 on 2026-11-20". That is all the `booking.confirmed` event carries: flight number and date per flight, names per passenger, the total. A traveller expects what every airline sends: where each flight goes, when it leaves and lands in local time, who sits where, and a document to keep or print. Nothing else in SkyHub sends seats per flight at booking time either.

The booking service already knows all of it when it books: pricing looks up every flight in the flight catalog (route, departure and arrival, ADR-0036), and the booking holds each passenger's seat on each flight.

## Options considered

1. **The notification service asks flight-search and booking for the details when it sends.** Rejected: the email would describe the flights as they are when it is sent, not as they were booked, and the notification service would need a way to read bookings, which only booking owns.
2. **The booking service puts the details in the event** (route, departure and arrival times, and each passenger's seats), taken from the same flight answers it priced the trip with. Chosen: the email describes exactly what was charged for, and the event stays the one message the notification service needs.
3. **A new event version (`booking.confirmed` v2).** Rejected: the change only adds fields. The notification service already ignores unknown fields and treats missing ones as absent, so version 1 grows without breaking either side.

For the PDF: Apache PDFBox (Apache 2.0, maintained by the Apache Software Foundation), not an HTML-to-PDF renderer, which would bring a browser engine or a large layout library for one page.

## Decision

- **The contract grows, still version 1** (`api-specs/events/booking-confirmed.v1.example.json`): each segment adds `origin`, `destination`, `departure` and `arrival` (UTC instants; `arrival` is left out when flight-search does not know it), and each passenger adds `seats`, one per segment in segment order.
- **The booking service fills them from the trip it priced.** `PricingService.price` returns the total and the flight details it looked up; `BookingService` passes both to the event. No extra call to flight-search, and none inside the booking transaction.
- **The confirmation becomes an itinerary** when the event has the details: per flight a card headed with the flight number and date, the route as `CMB 02:00 → DXB 05:50` in **local time at each airport** (with `+1` when it lands on a later day), the airport names, and each passenger with their seat. An event without the details (sent before this change) gets the old email.
- **`HtmlBody` learns one more shape** (ADR-0086): a card row that is a route line becomes a large route with an arrow. The plain text still says the same thing.
- **A PDF is attached**: `SkyHub-e-ticket-<reference>.pdf`, one A4 page in the SkyHub colours with the reference, passengers, each flight (route, local times and airports), seats, and the total paid. It is drawn by an `ETickets` port with a PDFBox adapter, like the boarding-pass pictures (ADR-0074). If the PDF cannot be drawn, the email goes without it and the failure is counted and logged without any personal data, because the confirmation is what matters.
- **It says plainly what it is not:** "Itinerary receipt. SkyHub is a portfolio project, not a real airline; this document is not valid for travel." It has no ticket numbers (SkyHub issues none) and no barcode: the boarding pass is the credential, and it comes at check-in (ADR-0074).
- **Airport names and time zones** are a small table in the notification service, the same as the website's (`frontend/src/lib/airports.ts`). An unknown airport shows its code and UTC times.

## Consequences

- Positive: the email and the PDF look like an airline's, and describe exactly what was booked and charged.
- Positive: older events still produce an email; nothing on the topic has to be replayed.
- Negative: the event now holds seats and more of the itinerary. It is encrypted in the outbox like before (ADR-0019) and on the topic it is as private as the topic is; it holds nothing a traveller cannot see on their booking.
- Negative: **airport data now lives in three places** (flight-search's timetable codes, the website's table, the notification service's table). Serving it from flight-search is the follow-up the website's table already names.
- Negative: the PDF uses the standard PDF fonts, which cover Western European letters. A name outside them (Sinhala, Tamil, Arabic script) is printed with `?` for those letters on the PDF; the email shows it correctly. Airline names are normally in Latin letters, as on a passport, but embedding a Unicode font is the fix if that changes.
- Negative: the email grows by the PDF (about 3-5 KB).
- Not done: a PDF for the rebooked or changed booking, ticket numbers, the e-ticket on the website's booking page.
- An alert, `NotificationETicketsDropped`, and its runbook entry, for a confirmation that went without its PDF.

## Verification

- Booking service: the contract test writes the event and compares it with the example file field for field; a flight whose arrival flight-search does not know leaves `arrival` out; pricing hands back the flights it priced, in trip order; the booking passes them to the event.
- Notification service: it reads the example file and an event from before this change; the itinerary shows local times at each airport (Colombo UTC+5:30, Dubai UTC+4, London GMT), marks a landing a day later `(+1)`, gives each passenger their seat on each flight, leaves an unknown landing time out, and shows an unknown airport by its code in UTC; the email has a card per flight and keeps the old form for an old event; the HTML draws a route line large; the PDF is one page with the reference, passengers, flights, seats, total and the "not valid for travel" line, and a Sinhala name prints with `?` instead of failing; the service attaches the PDF as `SkyHub-e-ticket-<reference>.pdf`, sends without it (and counts it) when it cannot be drawn, and draws nothing for an old event.
- An itinerary confirmation for Mumbai to Dubai to London with two passengers, and its PDF, were sent to a throwaway Mailpit and looked at in Chrome and in Chrome's PDF viewer. Mailpit lists one attachment (the PDF) and one inline picture (the logo); its HTML check scores 93%. Those screenshots are in the README.
- `promtool check rules` passes with the new alert.
- `./mvnw clean verify`: booking 1056 tests and notification 209 tests, no failures.

Not verified: a booking made through the website end to end with the new event (the pieces are each tested, and the contract file joins them), real inboxes, printing the PDF.
