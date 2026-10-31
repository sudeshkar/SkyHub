# ADR-0034: My bookings: a signed-in traveller sees the bookings made with their address

**Status:** Accepted
**Date:** 2026-10-31

## Context

Accounts exist (ADR-0033) but show nothing. A guest finds one booking by its reference and last name (ADR-0011). A traveller with an account should see every booking made with their address and open any of them without the reference. The risk is the one ADR-0033 was built around: **a booking must only ever be shown to the person who owns the address on it.** Contact addresses are encrypted (ADR-0010), so they cannot be searched, and many bookings are older than any index.

## Options considered

1. **Decrypt and compare** - read every booking, decrypt the address, keep the matches. Correct, but cost grows with all bookings in the system, not with the traveller's own.
2. **Link bookings to accounts with an `account_id`** - needs a write at booking time for a person who may sign up later, and a second write when they do. A booking made before sign-up would never be linked without a backfill anyway.
3. **A keyed index on the booking's contact address** (the same HMAC the account table already uses) - a lookup by an indexed value, for old and new bookings alike. Nothing links the two tables, so there is nothing to keep in step.

## Decision

- **Option 3.** `pnr.contact_email_index` holds `EmailIndex.of(contactEmail)`, the keyed hash from ADR-0033. It is set when a booking is made. A partial index on `(contact_email_index, created_at DESC)` serves the list in index order.
- **Old bookings are filled in by a backfill that runs at start-up** (`ContactEmailIndexBackfill`). It walks the unindexed rows in batches of 500 by primary key, so memory is bounded and a crash resumes where it left off; it changes only rows whose index is still empty, so it is safe beside live bookings, and does nothing once every row has an index. A partial index on the empty rows keeps each batch cheap and shrinks to nothing as the backfill finishes.
- **Two new endpoints, both for an `account` token only** (a booking `manage` token or a `staff` token is refused, and the other way round):
  - `GET /api/v1/accounts/me/bookings` returns the newest 50, each as a summary (reference, status, flights, passenger count, when it was made), with `truncated` set when there are more. A summary holds no names, seats or addresses.
  - `POST /api/v1/accounts/me/bookings/{locator}/sessions` returns the same session as the guest flow (a 15-minute manage token and the booking) **if and only if the booking's address index equals the account's.** Any other reference, whether it exists or not, returns the same 404.
- **Owning a booking is not a new kind of token.** The result is an ordinary manage token for that one booking, so every existing manage endpoint, rule and test applies unchanged.
- **Reading goes through a port** (`BookingsByAddress`) with a JDBC adapter that runs three queries whatever the size of the list (the page, their flights, their passenger counts), not one per booking.
- **Only a confirmed address can own bookings.** An account token is issued only after a password was chosen with the emailed link, which is what proves the address (ADR-0033). There is no way to attach an unproven address.
- **New code is added beside old:** a `MyBookings` API tag, controller and service; nothing in the guest flow, the account service or the manage service changed behaviour.
- **Monitoring:** `skyhub_my_bookings_total{outcome}` (listed, opened, denied) and `skyhub_contact_index_backfilled_total`; a runbook entry. No alert: a burst of `denied` is covered by the existing rate limits, and an account token already needs a password.

## Consequences

- Positive: the list costs what the traveller's own bookings cost, however many bookings exist.
- Positive: no new credential and no second path to a booking; ownership is one equality on a keyed hash.
- Positive: bookings made before sign-up appear at once, with no linking step.
- Negative: **the index is a keyed hash of the address, so the same address always gives the same value.** Anyone who can read the database can see which bookings share an address, though not what it is, and cannot compute it without the key. That is the trade for being able to look up at all.
- Negative: **a booking made for someone else with your address is shown to you.** The address on the booking, not the passenger, decides. That matches how the confirmation email is already sent.
- Negative: a traveller cannot yet change their address, so their bookings stay with the old one. Saved passengers are also later.
- Negative: only the newest 50 are listed; paging is not built. (Added later: ADR-0038.)
- Negative: the address is matched ignoring case but not dots or `+tags` (as in ADR-0033).
- Negative: the backfill decrypts each old address once, on the first start after the upgrade. It is bounded by batches, but a very large table makes that start slower.
