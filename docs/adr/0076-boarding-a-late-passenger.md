# ADR-0076: Boarding a passenger after the gate has closed

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0063 closed boarding 10 minutes before departure and named its own gap: *a closed window cannot be overridden.* A passenger who reaches the gate in the last minutes, held at security or delayed by a late connection, cannot be boarded by anyone, and the flight is still on the stand. In practice a supervisor decides, sometimes against the clock, and the system should record that they did, why, and who they were, instead of the only way through being the database.

## Options considered

1. **Let the gate agent override the closed gate.** Rejected: the person at the gate would be able to overrule the gate, which defeats the rule, and a stolen gate token would erase it (ADR-0060 made that token almost powerless on purpose).
2. **Widen or remove the window.** Rejected: the window is right for the ordinary case; the problem is the exception, not the rule.
3. **A separate action for operations and administrators, with a reason, only while the flight is still due to be there, recorded and visible.** Chosen. It is a second door into the same boarding, so every other rule still applies.

## Decision

- **`POST /api/v1/ops/boarding-passes/late-boardings`** with `{barcode, reason}`. The pass is checked exactly as in ADR-0056 and ADR-0060; if it is good the passenger **boards**, by the same one row in `boarded_passenger`, so a passenger still boards **once** however they got on, and the first scan still counts. The answer has the same shape as an ordinary boarding.
- **Authority: `OPS` and `ADMIN` only** (`StaffRole.mayBoardLatePassengers`, written out like the others). A gate agent and a traveller are refused with 403, no token with 401. The person is taken from the staff token.
- **Only for a flight that is closed and not yet gone.** Late boarding is refused with:
  - `BOARDING_STILL_OPEN` while boarding is open (the ordinary way is the right one, so the override is never used when it is not needed);
  - `BOARDING_NOT_OPEN` before boarding opens;
  - `FLIGHT_DEPARTED` once the flight is due to have left (at or after the expected departure: delayed flights use the revised time, ADR-0075);
  - `FLIGHT_CANCELLED` for a cancelled flight, and the verifier's own reasons for a pass that is not good or has been used.
  A refusal says why and nothing about the passenger.
- **A reason is required** (1 to 200 characters, trimmed): `400` otherwise, before the pass is looked at. It is kept with the boarding (`boarded_passenger.late_reason`, migration V25), with who boarded them and when, and it **survives an undo** (ADR-0064): `boarding_reversal` copies it, so the record of a correction still shows the passenger had been boarded late.
- **Visible:** the who-has-boarded list (ADR-0065) marks each boarding `late` (a boolean; the reason stays in the database). The metric `skyhub_boarding_pass_boardings_total` gains `boarded_late`, `boarding_still_open` and `flight_departed` outcomes. The log carries the length of the reason and never its text.
- **Website:** a "Board late" form on `/ops` for operations and administrators (not shown to a gate agent): the pass, a reason and a tick-box confirmation. The shared refusal wording moves to one module used by the gate and this form. The who-has-boarded list shows a small "late" marker.
- **No alert on purpose.** It is a deliberate act by a person, like recording a disruption; the count and the marker are how a supervisor or an audit sees how often it happens. The runbook says how to look.

## Consequences

- Positive: the exception has an owner, a reason and a trace, and the person at the gate cannot use it or erase it.
- Positive: nothing else about boarding changed. The same checks, the same row, the same once-only rule; checking a pass still has no clock.
- Positive: undoing a late boarding keeps what was done and why.
- Negative: **the reason is free text** kept in the database as typed; it can hold personal data if staff write it there. It is not logged or sent anywhere else.
- Negative: **no limit on how often one supervisor can do it, and no alert.** Only the list and the metric show a pattern.
- Negative: **"due to have left" is the expected departure, not the aircraft's real movements.** There is no actual-departure signal in the system. A flight that is late pushing back can still board someone until its expected time; one that left early cannot be known to have.
- Negative: **it depends on the timetable and the delay record being right**, as the window does (ADR-0063).
- Negative: **the boarding is not attributed to who asked for it.** The record has the operations user who pressed the button, not the gate agent or passenger who asked.
- Not done: a per-route window (the other half of the ROADMAP item), a limit or alert on repeated use, and a way for the gate agent to request the override.

## Verification

Unit tests for the service (a late boarding with the trimmed reason, who and when; a missing, blank or too-long reason refused before anything is looked at; refused while open, before it opens, at and after departure and for a cancelled flight; still refusing a bad or used pass; the ordinary gate still refused when closed), for the role rule (operations and administrators only), for the controller with the real security rules (200, the person from the token, no-store, a refusal as a 200 with only the reason, 401 and 403, 400 for a bad request) and for the who-has-boarded list (the `late` flag). Integration tests on real PostgreSQL through the real filter chain, with the closing time moved so the test flight is closed and not yet gone: operations board late, the reason is stored, the list marks it, the ordinary gate is refused, a second late boarding is refused as already boarded; a gate agent, a traveller and no token are refused and a missing or blank reason is a 400; the reason is kept in the reversal record after an undo and the passenger can then be boarded late again. Website tests for the client call, the action (trimmed values, confirmation, limits, refusals, sign-out, no answer, rate limit), the form, the shared wording and the list's marker. The whole booking build ran clean with 1020 tests and the frontend with 511, `tsc`, `eslint` and `next build` clean. The live check was **not** run and nothing was clicked through in a browser.
