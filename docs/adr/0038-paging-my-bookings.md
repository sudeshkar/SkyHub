# ADR-0038: Paging "Your bookings" past the newest 50

**Status:** Accepted
**Date:** 2026-11-01

## Context

"Your bookings" (ADR-0034) shows the newest 50 and says there are older ones. A traveller (or a travel manager booking for a team with one address) with more than 50 cannot see the rest, and there is no way to ask for them. The list is read straight from an index on the contact address, so the question is how to ask for "the next 50" without making later pages cost more than the first.

## Options considered

1. **Offset paging** (`?page=3`) - simple, but the database has to walk past every earlier row to find page 3, so the cost grows with how far back you go. A booking made while someone is reading shifts every later page, so a row can be shown twice or missed.
2. **Return everything** - the cost the 50 limit was put there to avoid.
3. **Keyset paging: "after the last booking you saw"** - the next page starts from a position in the index, so every page costs about the same, and a new booking cannot shift pages that were already read.

## Decision

- **Option 3.** Bookings are ordered by `(created_at, locator)` newest first, and a page begins strictly after a position in that order. `locator` is the tie-break, because two bookings can be made in the same instant; it is unique, so the order is total and no booking is skipped or repeated. The index from ADR-0034 (`contact_email_index, created_at DESC`) finds the starting point, and each page reads at most 51 rows (one extra, to know whether there is another page).
- **The position is an opaque cursor.** `GET /api/v1/accounts/me/bookings?cursor=` takes the cursor from the previous answer's `nextCursor`, which is absent on the last page. `truncated` stays, and is true exactly when `nextCursor` is present, so nothing that read it breaks. The cursor is the creation time and reference of the last booking shown, encoded; callers must not build one.
- **A cursor cannot reach anyone else's bookings.** It is only ever a lower bound inside the account's own index, so even a tampered one can only move within that traveller's bookings. A malformed cursor is a 400, and the page then offers the newest bookings.
- **Nothing else changes.** The page size stays 50, ownership is still one equality on the keyed hash, and opening a booking is untouched.
- **The website** shows an "Older bookings" link to the next page and a "Newest bookings" link back. The cursor is in the address, which is acceptable: it holds only a time and the reference of a booking the traveller already sees, the page is kept out of search results, and the booking itself is still opened by a form post.

## Consequences

- Positive: any page costs the same as the first, however many bookings there are, and walking all pages shows each booking once.
- Positive: new bookings appear at the top and never disturb pages already read.
- Positive: no new storage, index or column.
- Negative: **there is no "page 5" or total count.** Keyset paging only goes forward from where you are (the website can go back to the newest, not one page back). That is the price of constant cost.
- Negative: a booking's creation time is the only sort; there is no sorting by flight date or filtering by status or flight.
- Negative: a cursor is only meaningful against the data it was made from; a booking made later, with an earlier timestamp than the one that was shown last, cannot happen (time moves forward), but deleting bookings is not a feature, so no cursor can point at a removed row.
- Follow-up: filtering the list (upcoming and past, since ADR-0042), and changing the account's email address (since ADR-0039).
