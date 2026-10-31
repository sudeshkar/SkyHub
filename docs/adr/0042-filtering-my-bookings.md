# ADR-0042: Filtering "Your bookings" into upcoming and past

**Status:** Accepted
**Date:** 2026-11-02

## Context

"Your bookings" is a single list, newest first, in pages (ADR-0038). Someone who flies often mostly wants the trips still ahead of them, and today those are mixed in with every trip they ever made, in the order the bookings were made rather than the order they fly. ADR-0038 named filtering as the follow-up.

## Options considered

1. **Filter in the browser** - the page already holds up to 50 bookings, but a traveller's next trip can be on an older page, so a filter over one page answers the wrong question.
2. **Sort by flight date** - the order people expect for upcoming trips, but it needs an index on a value that lives in another table, and it changes the paging position (ADR-0038) from "when it was made" to something that moves when a booking changes.
3. **Filter in the query, keep the order** - the same newest-first list and the same cursor, with a condition that decides which bookings qualify. Nothing about paging changes.

## Decision

- **Option 3.** `GET /api/v1/accounts/me/bookings?when=` takes `all` (the default and today's behaviour), `upcoming` or `past`. Anything else is a 400.
- **Upcoming** means a confirmed booking with at least one flight whose departure date is today or later. **Past** means every other booking: all its flights are behind it, or it was cancelled. A cancelled booking for next month is therefore under "past", and the website says "Past and cancelled" so nobody is surprised.
- **"Today" is the UTC date,** as every date in SkyHub is (ADR-0005), taken from the service's clock, not from the browser. A flight that left this morning is still "upcoming" until the day ends; the booking service stores departure dates, not minutes.
- **The same order and the same cursor.** A cursor is a position in the account's list, so a page from one filter can be followed by the next page of that filter; sending a cursor from one filter with another just continues from that position in the other list. Each page is checked in one query: the condition is `status = 'CONFIRMED' AND EXISTS (flight on or after today)`, or its negation.
- **Ownership is untouched:** still one equality on the keyed hash of the address. The filter narrows a traveller's own bookings; it cannot widen them.
- **The website** shows "All", "Upcoming" and "Past and cancelled" above the list, keeps the choice in the address (`?when=`) beside the cursor, and explains an empty filter rather than saying the traveller has no bookings.

## Consequences

- Positive: "what am I flying next" is one click, on any page of a long history.
- Positive: no new index, column or table, and paging behaves exactly as before.
- Positive: the traveller's own bookings only; no new way to reach anyone else's.
- Negative: **a filter that matches few of a traveller's many bookings reads past the ones that do not.** The query walks the traveller's bookings in order until it has a page, so asking for "upcoming" when you have five hundred old bookings and two coming reads about five hundred rows (of that traveller only, never of the system). That is still bounded by their own history, which is small, and each page stops as soon as it is full.
- Negative (ended by ADR-0044, which lists upcoming trips soonest first): **upcoming trips are listed by when they were booked, not when they fly.** A trip booked long ago for next week can be on page three. Sorting by flight date is the next step and needs its own index and a different cursor.
- Negative: a booking with several flights is "upcoming" until its **last** flight is behind it, even if the first one has gone.
- Negative: a flight that left earlier today still counts as upcoming until midnight UTC, and there is no time zone for the traveller.
- Negative: there is no filter by flight, route or status beyond this.
- Follow-up: sort upcoming by departure date; search by reference or flight; saved passengers.
