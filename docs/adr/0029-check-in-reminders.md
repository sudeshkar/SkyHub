# ADR-0029: Check-in-open reminders

**Status:** Accepted
**Date:** 2026-10-30

## Context

Check-in opens 24 hours before departure (ADR-0013) but nobody tells the traveller. Every email so far (ADR-0019, 0020, 0028) is triggered by something a person did. This one is triggered by **time passing**, which needs something to notice. It must also stay cheap as bookings grow, because most of what it looks at is not due.

Two facts make it harder than a timer:
- The departure *time* is not in the booking. It comes from flight search, and a delay (ADR-0014) changes when check-in opens.
- The service runs as several instances, and none may send a traveller two reminders.

## Options considered

1. **A delayed job per booking** (queue a message for "24 hours before departure" when a booking is made). A delay or a rebooking would make every queued message wrong, and cancelling them needs bookkeeping for each.
2. **Scan every booking each time and check its flight.** Cost grows with all bookings ever made, and it asks flight search about the same flight thousands of times.
3. **Sweep per flight.** Ask which flights have someone waiting, work out for each *flight* whether its window is open, and only then read that flight's bookings.

## Decision

- **A sweep every five minutes**, per flight (option 3). It finds the distinct flights departing from yesterday to the day check-in could be open, with at least one confirmed booking that has not been reminded, checked in, or cancelled and whose flight is not cancelled. For each, it works out the departure (the revised time if the flight is delayed, otherwise the timetable) and tests the window. A flight that is not open yet, or has closed, costs one lookup however many people are booked on it. An index on the departure date keeps the query away from old bookings.
- **A flight that is open is worked through a page at a time** (200 bookings per transaction), up to 5,000 per sweep. What is left is picked up by the next sweep, so one huge flight cannot hold a transaction for long or starve the others.
- **A reminder is claimed by inserting a row** in `check_in_reminder`, whose primary key is the booking and flight. If two instances sweep at once, one insert wins and the other queues nothing. The claim and the email event are written in the same transaction (the outbox, ADR-0019), so a reminder is never claimed without being queued, and never queued twice.
- **The reminder is "check-in is open", sent in the first sweep after the window opens,** with the departure and the closing time. It is not sent after check-in has closed.
- **The timer is a separate small class** from the sweep, so tests run a sweep on demand and switch the timer off.
- **Monitoring:** `skyhub_checkin_reminders_total{outcome=sent|flight_unavailable|failed}`. An alert fires when the sweep fails repeatedly; a runbook entry says what to check.
- **The event is `booking.check-in-open`,** with a shared example read by both services' tests, and one email text.

## Consequences

- Positive: cost depends on the number of flights in the next day or two, not on the number of bookings. Tested: a flight whose window is closed reads no bookings.
- Positive: a delay moves the reminder automatically, because the departure is re-read on every sweep.
- Positive: safe with several instances, and a failed sweep simply repeats.
- Negative: **the reminder can be up to five minutes late.**
- Negative: **a booking made after check-in opens is reminded at the next sweep,** which may be only minutes after they booked. It is accurate but possibly unnecessary.
- Negative: the "which flights have someone waiting" query still reads every unreminded booking in the window. That is fine at this size; at far larger scale a table of flights to watch would replace it.
- Negative: the bookings of a page are loaded one by one. A batch load is the next optimisation.
- Negative: if flight search is down, flights whose time is unknown are skipped until it answers; if it is down until check-in closes, those travellers get no reminder.
- Negative: per-passenger check-in does not exist yet, so one email goes to the booking contact for everyone.
