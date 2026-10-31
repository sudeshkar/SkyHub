# ADR-0044: Upcoming bookings, soonest flight first

**Status:** Accepted
**Date:** 2026-11-02

## Context

"Upcoming" (ADR-0042) lists a traveller's confirmed bookings with a flight still ahead, in the order they were made. A trip booked six months ago for next week can sit on the third page, behind trips booked last week for next spring. The question "what am I flying next" is answered by the flight date, not the booking date, and ADR-0042 named that as its main negative.

## Options considered

1. **Sort the page in the browser** - only reorders the 50 already fetched; the next trip can be on another page.
2. **A stored "next departure" column on the booking** - fast to read, but it changes with the clock (a booking's next flight moves on as flights depart), so it would have to be rewritten by a job, and a rebooking would have to remember to update it. A second copy of something derivable.
3. **Compute each booking's next flight date in the query and page on it** - nothing stored, correct by construction, a second cursor shape.

## Decision

- **Option 3, for `upcoming` only.** `GET /api/v1/accounts/me/bookings?when=upcoming` lists the soonest trip first. A booking's position is the date of its **next** flight, the earliest flight date that is today or later (a trip with one flight gone and one to come is positioned by the one to come). Bookings with the same next date are ordered by reference, so the order is total.
- **`all` and `past` are unchanged:** newest booked first, with the cursor from ADR-0038. A cursor from one of those cannot be used for `upcoming`, nor the other way round; the wrong kind is a 400 ("that page of bookings is not valid") and the website drops the cursor when the filter changes.
- **A second cursor, for the upcoming order:** the next flight date and the reference of the last booking shown, encoded the same opaque way. It is only ever a lower bound inside the account's own bookings, so a forged one cannot reach anyone else's (as for the first, ADR-0038).
- **How it is read:** one query computes each of the traveller's confirmed bookings' next flight date with a subquery over their flights, keeps those that have one, and returns the page strictly after the cursor in `(next date, reference)` order. The page still reads one extra row to know whether there is another.
- **Nothing is stored and nothing is added to the schema or a contract shape:** one more cursor format, one more query, the same response.
- **The website** says "soonest first" under Upcoming and labels the paging links "Later trips" and "Soonest trips" there, so the arrows mean what they say.

## Consequences

- Positive: the trip you are flying next is the first thing on the first page, however long ago it was booked.
- Positive: derived from the flights themselves, so a rebooking (ADR-0023) moves a trip in the list with no extra work and nothing can be out of date.
- Positive: ownership is still one equality on the keyed hash; the order cannot widen what a traveller sees.
- Negative: **the cost of a page now grows with the traveller's upcoming bookings, not with the page.** A computed value cannot use an index, so each page computes the next date for all of the traveller's confirmed bookings, sorts them, and takes the page. That is bounded by one traveller's own bookings (tens, not millions, and never the system's), but it is no longer the constant per-page cost of ADR-0038. If a traveller ever had thousands of upcoming bookings, a stored column would be the answer (option 2).
- Negative: **two kinds of cursor** that must not be mixed, which is a small trap for any future caller; the service refuses a mismatch rather than guessing.
- Negative: a booking is positioned by its next flight's **date**, not time, so two trips on the same day are ordered by reference.
- Negative: the past list is still newest booked first, not most recently flown; past trips are rarely looked up in order, so this is left.
- Follow-up: order past trips by the date they were last flown; a stored next-departure column if a traveller's list ever outgrows the query.
