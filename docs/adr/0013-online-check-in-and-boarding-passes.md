# ADR-0013: Online check-in and signed boarding passes

**Status:** Accepted
**Date:** 2026-10-29

## Context

After booking, a traveller needs to check in for each flight and get a boarding pass. Check-in is only possible inside a window (airlines open it 24 hours before departure and close it before the gate does), it hands out a boarding sequence number per flight, and the pass carries a barcode the gate can trust without calling us.

The booking service knows a flight only by number and date, not when it leaves, and a PNR should not grow every time a new feature touches a booking.

## Options considered

1. **Add check-in columns to the PNR and its passengers** — one table fewer, but every check-in feature edits the booking aggregate, and a check-in write would contend with seat changes on the same row version.
2. **A separate `check_in` record per passenger per flight** — the booking stays untouched; check-in has its own table and its own uniqueness rule.
3. **Number boarding passes with `count(*) + 1`** — two travellers checking in at once get the same number.

## Decision

Option 2, with an atomic counter.

- **Window.** Check-in is open from 24 hours to 60 minutes before departure (`skyhub.booking.check-in.opens-before` / `closes-before`). `CheckInWindow` is a small domain object that takes the departure time and the clock's "now" and says *not open yet* or *closed*; both answer 409 with a message that names the time. The departure time comes from the flight-search service through the existing `FlightCatalog` port, which gains `departure(flight, date)`; the adapter caches it the same way as "does it operate".
- **Whole party per flight.** `POST /pnrs/{locator}/check-ins` with `{segmentIndex}` checks in every passenger on that flight at once. Repeating it returns the same passes (idempotent): nothing is renumbered. A unique key on (locator, segment, passenger) makes this safe even under a double-click race.
- **Boarding sequence.** One row per flight and date holds the next number; a single `INSERT … ON CONFLICT DO UPDATE … RETURNING` reserves a block for the whole party. No read-then-write, so concurrent check-ins cannot receive the same number, and no lock is held beyond that statement.
- **Signed barcode.** A pass carries a compact text payload (name, locator, route, flight, date, seat, sequence) followed by an HMAC-SHA256 signature made with `SKYHUB_BOARDING_KEY`. A gate holding the key can verify a pass offline; changing any field breaks the signature. `BoardingPassSigner` is a port, so a real BCBP/PDF417 encoder or an asymmetric signature (gates would then need only a public key) can replace it without touching the use case.
- **Seats are frozen once checked in.** A seat change for a flight that is checked in is refused (409); the traveller would have to be checked out first, which is out of scope. Cancelling still works and invalidates the passes: they are only returned for an active booking.
- **Access.** Both endpoints need the booking's manage token (ADR-0011).

## Consequences

- Positive: the booking aggregate is unchanged; check-in adds a table, a use case and two endpoints.
- Positive: unique numbering and idempotency are enforced by the database, not by hoping requests do not overlap.
- Negative: boarding numbers can have gaps (a rolled-back check-in still consumed its block). Gaps are harmless; duplicates would not be.
- Negative: a symmetric key must be shared with every gate verifier. Moving to an asymmetric signature is the follow-up.
- Follow-up: per-passenger check-in, check-out to allow seat changes, a QR/PDF417 image and wallet pass, a "check-in opens" email.
