# ADR-0055: Checking out, so the seats can change again

**Status:** Accepted
**Date:** 2026-11-04

## Context

Once a flight is checked in (ADR-0013), its seats are frozen: the boarding passes carry the seat, so changing it afterwards would leave a pass that names the wrong one. The same check also stops a rebooking (ADR-0022): a checked-in booking cannot be moved to another flight. ADR-0013 said "the traveller would have to be checked out first, which is out of scope". A traveller who checks in and then wants a window seat, or whose flight is cancelled after they checked in, has no way back except a human.

## Options considered

1. **Let a seat change on a checked-in flight rewrite the passes.** Hides the problem: a pass already printed or saved would be wrong, and boarding numbers would have to be re-issued without saying so.
2. **A staff-only override.** Needs staff for something the traveller can do.
3. **Let the traveller undo the check-in, as a separate step.** Explicit: the old passes stop applying, and checking in again produces new ones.

## Decision

**Option 3.** `DELETE /api/v1/pnrs/{locator}/check-ins/{segmentIndex}`, with the booking's own manage token, like check-in.

- **Whole party, one flight**, the same unit as check-in. Other flights of the booking stay checked in.
- **Allowed until check-in closes** (60 minutes before departure, by the revised time if the flight was delayed): after that the passes are what the gate works from. A closed window is the same 409 "Check-in unavailable" as for checking in.
- **Always allowed for a cancelled flight**, because a booking that is checked in cannot be rebooked (ADR-0022), and the traveller should not be stuck. A cancelled booking cannot check out (409).
- **Idempotent:** a flight that is not checked in is also 204, so a double press or a retry is harmless.
- **Checking in again numbers the party afresh.** The boarding sequence never goes backwards, so the old numbers are not reused (gaps, as before: ADR-0013).
- The old passes stop being shown (`GET` is 404 "Not checked in").
- The website shows "Check out" beside "Checked in", and the seat buttons of that flight work again.
- A count, `skyhub_check_ins_total{outcome="checked_out"}`, joins the existing outcomes; a refused check-out counts as `refused`.

## Consequences

- Positive: a traveller can fix a seat after checking in, and a checked-in booking on a cancelled flight can be moved, with no staff.
- Negative: **a pass that was already issued is not revoked.** It is signed (ADR-0013) and a gate that only checks the signature would still accept it, with the old seat and number. There is no gate system in this repository and no revocation list, so nothing refuses it. This has to be solved at the gate (check the booking, not only the signature) before this is used in a real airport; it is the reason check-out closes with check-in.
- Negative: check-out is a delete. The record that someone checked in and out is in the logs (`Checked out`) and the metric, not in a table.
- Negative: a traveller can check in and out repeatedly, each time consuming boarding numbers; the sequence has gaps. The website limits requests like any other manage action.
- Not done: per-passenger check-in and check-out (the party moves together), and a QR/PDF417 image of the pass.
