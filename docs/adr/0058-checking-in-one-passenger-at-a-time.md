# ADR-0058: Checking in one passenger at a time

**Status:** Accepted
**Date:** 2026-11-07

## Context

Check-in was for the whole party (ADR-0013) and check-out too (ADR-0055). A family where one person wants to check in now, or cannot, or wants a different seat after checking in, had no way to do that: one press moved everybody, and one checked-in passenger froze every seat on the flight. The storage already held one row per passenger per flight; only the rules treated the party as a unit.

## Decision

Passengers are named by their position on the booking. Nothing in the data model changes; the unit of the rules does.

- **Check-in:** `passengerIndexes` is optional on `POST /pnrs/{locator}/check-ins`. Absent or empty means **everyone who is not yet in** (so existing callers behave as before). Named passengers are checked in; one who is already in is left as they are and **is not renumbered**. Only the newly checked-in get boarding numbers, in one block.
- **The answer holds the passes of everyone who is checked in on the flight afterwards**, as `GET` does, not only those asked for, so a screen replaces its state with one answer. It is 201 if the call checked in anyone new, otherwise 200.
- **Idempotent, even after the window closes,** when everyone asked for is already in. Checking in someone new after the window closed is refused, even if others are in.
- **Check-out:** `passengerIndex` (repeatable) is optional on `DELETE .../check-ins/{segmentIndex}`; absent means everyone who is in. Passengers who are not in are left alone; asking again is a 204. The window rule of ADR-0055 is unchanged.
- **A seat freezes per passenger:** a checked-in passenger's seat cannot change; the others' can (409 "Already checked in" names the passenger).
- **A pass says which passenger it is for** (`passengerIndex`), so a screen can tell. The signed text on the pass is unchanged.
- **A bad index is a 400** (a passenger the booking does not have). The contract allows at most nine, like a booking.
- **The gate check (ADR-0056) needed one correction:** a pass for a passenger who is not in, while another passenger on the booking is, used to be called `SUPERSEDED`. It is `NOT_CHECKED_IN` when someone with the name on the pass is on the booking and none of them is in.
- **The website** shows each passenger's own "Check in" / "Check out" on a booking of more than one, keeps the flight's buttons for everyone left, and freezes only the checked-in passenger's seat.
- **Rebooking is unchanged:** a booking with anyone checked in on a flight cannot be moved off it (ADR-0022), because moving it would strand that pass.

## Consequences

- Positive: a family can check in as people arrive, and a late seat change for one person no longer needs the whole party checked out.
- Positive: no schema change, and the old whole-party calls mean the same as before.
- Negative: **the answer is everyone who is in, not who was asked for,** which a caller that expected exactly the passes it asked for must not assume.
- Negative: **two passengers with the same name** on one booking cannot be told apart by the gate check's reason (it looks at the name on the pass); the verdict of "valid" is still exact, because it compares the whole pass.
- Negative: a passenger checked out and in again gets a **new** boarding number, as before; the old pass is refused (ADR-0056), and the numbers have gaps.
- Not done: a passenger who cannot be checked in because of something other than the window (documents, for example), and checking in for a different flight in one call.
