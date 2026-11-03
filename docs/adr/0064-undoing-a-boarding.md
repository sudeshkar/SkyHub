# ADR-0064: Undoing a wrong boarding

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0060 let a passenger board once and named its own gap: *boarding cannot be undone. A wrong scan has no correction short of the database.* The scan is easy to get wrong (the passenger behind in the queue, a pass held up on a phone, a second gate), and a passenger who has boarded cannot check out, so a mistaken boarding leaves the real passenger refused as `ALREADY_BOARDED` and the record wrong, with only SQL as a way out.

## Options considered

1. **Undo by scanning the pass again.** Rejected: a boarded pass is refused with `ALREADY_BOARDED` and no details (ADR-0056), by design, so that a gate cannot be used to learn who a pass is for. An undo that reads the pass would need to look the passenger up for a refused pass, which weakens that rule.
2. **Let the gate agent undo their own scan.** Rejected: the person who made a scan must not be the person who can erase it, and a stolen gate token would then be able to un-board passengers. ADR-0060 made the GATE role able to do almost nothing on purpose.
3. **Overwrite or delete the boarding with no trace.** Rejected: a correction that leaves no record is a way to hide a boarding that happened.
4. **A reversal by operations or an administrator, by reference, with a required reason, recorded in an append-only table.** Chosen.

## Decision

- **`POST /api/v1/ops/boardings/reversals`** with `{locator, segmentIndex, passengerIndex, reason}`. `204` when the boarding was taken back; `404 "Not boarded"` when that passenger has not boarded that flight (this is also what a second identical request gets, so asking twice never records two reversals); `400` for a missing, blank or over-200-character reason or a negative index.
- **Authority: `OPS` and `ADMIN` only** (`StaffRole.mayUndoBoarding`, written out like the other rules). A `GATE` token and a traveller token are refused with `403`, and no token with `401`. The person is read from the staff token, never from the request body.
- **One atomic statement.** `BoardedPassengers.undo` runs a `DELETE ... RETURNING` that feeds an `INSERT` into the new table in a single statement, so no transaction is needed and two requests racing for the same boarding produce exactly one reversal row (the loser deletes nothing and is told "not boarded").
- **Audit:** `boarding_reversal` (migration V23) is append-only: it keeps who scanned and when, who reversed and when, and the reason. No code updates or deletes it.
- **The passenger stays checked in**, so they can board again with the same pass, inside the boarding window (ADR-0063).
- **`BoardingReversalService`** holds the rule (a reason of 1 to 200 characters, trimmed) and the metric; the port keeps the SQL out of the use case.
- **Metric:** `skyhub_boarding_pass_reversals_total{outcome=reversed|not_boarded}`. The reason is free text typed by staff, so it is never a label and never logged: the log line carries only its length.
- **No alert on purpose.** An undo is a deliberate act by a person, like recording a disruption; there is nothing for a second person to respond to. The runbook says how to find who did what.
- **Website:** an "Undo a boarding" form on `/ops`, shown to operations and administrators and not to a gate agent. It counts flights and passengers from one, asks for a reason and a tick-box confirmation, and keeps what was typed when it is refused.

## Consequences

- Positive: a wrong scan can be corrected without database access, by someone accountable, and the correction is kept.
- Positive: the gate agent's token stays nearly powerless (ADR-0060), and the pass-lookup rule of ADR-0056 is untouched.
- Negative: **the person doing the undo needs the booking reference and the passenger and flight numbers.** A boarded pass shows none of these to a refused scan; the ops page does not yet show who has boarded. A screen listing boarded passengers per flight would make this easier.
- Negative: **a corrected boarding leaves no trace on the pass or the booking.** Only `boarding_reversal` shows it.
- Negative: **the reason is free text** kept in the database as typed (it can hold personal data if staff write it there). It is not logged or sent anywhere else.
- Negative: **there is no limit on how often one boarding can be undone and redone**, and no alert on it. A person doing so repeatedly is visible only in the table.
- Not done: undoing for a passenger whose booking was cancelled after boarding (the undo works, but the pass is refused anyway), and showing the history of reversals in the website.

## Verification

Unit tests for the service and the role rule; controller tests with the real security rules (204, 404, 400, and 401/403 for a gate agent, a traveller and nobody); integration tests on real PostgreSQL through the real filter chain (undo then board again, the audit row, the refusals, and a concurrent double undo leaving exactly one reversal row); website tests for the action and the form. Full booking build, frontend tests, `tsc`, `eslint` and `next build` were run. The live check was **not** run for this slice, and the new form was **not** clicked through in a browser.
