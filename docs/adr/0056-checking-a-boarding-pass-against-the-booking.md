# ADR-0056: Checking a boarding pass against the booking

**Status:** Accepted
**Date:** 2026-11-05

## Context

A boarding pass carries a signed barcode (ADR-0013), and `signer.verify` says only that *we issued this text and nobody changed it*. That is not the same as *this pass still applies*. ADR-0055 let a traveller check out and check in again: the old pass keeps a genuine signature with the old seat and number. A pass for a booking that was cancelled, or for a flight that was cancelled, is just as genuine. A gate that trusts the signature alone would let all of them board, and ADR-0055 named this as its main negative: "a real gate must check the booking, not only the signature".

## Options considered

1. **A revocation list** of passes that no longer apply. Another store to keep right, and every way a pass can stop applying has to remember to add to it.
2. **Put an expiry or a version in the pass.** The pass is printed or saved on a phone; it cannot be changed after the fact, so it cannot be told it has been replaced.
3. **Ask the booking.** The current state already says which pass is valid: it is the one that would be issued right now. Nothing new is stored, and nothing can be forgotten.

## Decision

**Option 3.** `POST /api/v1/ops/boarding-passes/verification` with `{barcode}`, for staff (every role; ADR-0060 adds a `GATE` role that can do only this).

A pass is **good** only if it is signed by us **and** is exactly the pass that would be issued for that booking now (same passenger, flight, seat and boarding number). Otherwise it is refused with one reason:

| Reason | Meaning |
|---|---|
| `BAD_SIGNATURE` | not signed by us, changed, too long, or not a pass at all. Nothing is looked up. |
| `UNKNOWN_BOOKING` | signed by us but the booking does not exist |
| `BOOKING_CANCELLED` | the booking was cancelled |
| `FLIGHT_CANCELLED` | the flight was cancelled |
| `NOT_CHECKED_IN` | the flight is not checked in: never was, checked out (ADR-0055), or not on the booking |
| `SUPERSEDED` | a different pass is current: a new seat or a new check-in since |

- **Not valid is a 200**, because it is the answer, not an error.
- **A refused pass says only why, never who it was for**; only a good pass returns the passenger, flight, seat and number. So the endpoint cannot be used to learn a name from a locator.
- **Made-up text costs nothing:** the signature is checked before any lookup, so it cannot be used to probe bookings.
- **One place writes the payload and one reads it** (`BoardingPassPayload`), used by check-in and by this check, so the two cannot drift apart. The name comes first and may hold spaces, so it is read from the right.
- A count, `skyhub_boarding_pass_verifications_total{outcome}`, and a log line without any personal data.

## Consequences

- Positive: the gap named in ADR-0055 is closed in one place, with no new store.
- Positive: it is correct by construction: whatever the booking says now is what is valid, including cases nobody thought of.
- Negative: **a gate must be online** (it asks the service). An offline scanner can only check the signature, with the old weakness. Nothing here makes a scanner do either; there is no gate system in this repository.
- Negative: a looked-up check costs a few queries per scan. Nothing limits the rate of a staff token; the scale of a real gate (many scanners) has not been measured.
- Negative: any staff member can ask. The `GATE` role of ADR-0060 is the least privilege for it.
- Not done here: boarding (marking a passenger as boarded) and refusing a second scan of the same pass. ADR-0060 does both.
