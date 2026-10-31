# ADR-0051: Past bookings, most recently flown first

**Status:** Accepted

## Context

"Past" (ADR-0042) lists every booking that is not upcoming, in the order the bookings were made. The question a traveller asks of that list is "when did I last fly to Lisbon", which the flight date answers, not the booking date: a trip booked a year ahead and flown last week sits behind trips booked more recently and flown long ago. ADR-0044 fixed this for upcoming trips and named past as the remaining one.

## Options considered

1. **Sort the page in the browser** - only reorders the 50 already fetched; the trip flown most recently can be on another page.
2. **A stored "last flight" column** - a second copy of something derivable, which a rebooking would have to remember to update.
3. **Compute each booking's last flight date in the query and page on it** - nothing stored, correct by construction, a third cursor shape. The same choice as ADR-0044.

## Decision

- **Option 3, for `past` only.** `GET /api/v1/accounts/me/bookings?when=past` lists the booking whose last flight was most recent first. A booking's position is the date of its **last** flight (the latest flight date on it, whether or not it has been flown: a cancelled booking is positioned by the flights it would have had). Bookings with the same date are ordered by reference, higher first, so the order is total.
- **`all` stays newest booked first** with the cursor of ADR-0038; `upcoming` stays soonest first (ADR-0044).
- **A third cursor kind, `PastCursor`:** the last flight date and the reference of the last booking shown, encoded the same opaque way, with its own kind marker. Each of the three cursors is refused by the other two readers, so a cursor from one list is a 400 for another, never guessed at. Like the others it is only a bound inside the account's own bookings.
- **How it is read:** one query computes the last flight date of each of the traveller's bookings that is not upcoming, and returns the page strictly after the cursor in `(last date descending, reference descending)` order. The page still reads one extra row to know whether there is another.
- **The port is explicit about it:** `BookingsByAddress.newestAfter` is now only the newest-first order (`all`); `upcomingSoonest` and `pastMostRecent` are the other two. Asking `newestAfter` for `UPCOMING` or `PAST` is a programming error and throws, so a caller cannot get the wrong order by mistake.
- **No contract shape changes:** the response and parameters are the same; the API description now says which order each filter uses. The website needed no change, because the cursor is opaque and it already drops the cursor when the filter changes.

## Consequences

- Positive: the trip flown most recently is first on the first page, however long ago it was booked.
- Positive: derived from the flights themselves, so a rebooking moves a trip in the list with nothing stored to go stale.
- Negative: **the cost of a past page grows with the traveller's own non-upcoming bookings, not with the page**, as for upcoming (ADR-0044): a computed value cannot use an index. It is bounded by one traveller's bookings, never the system's. A stored column is the answer if one traveller ever had thousands.
- Negative: a cancelled booking for a flight that has not happened is positioned by its (future) flight date, so it appears first in the past list. That reads correctly as "the most recent trip on your account", and it is stated in the API description rather than hidden.
