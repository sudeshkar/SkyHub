# ADR-0060: Boarding, and a role that can only work the gate

**Status:** Accepted
**Date:** 2026-11-09

## Context

A staff member could check whether a scanned pass is still good (ADR-0056), but two things were missing, both named in that ADR:

- A good pass could be shown valid **any number of times**. Nothing recorded that the passenger had boarded.
- The only staff roles were operations and administrators. Whoever stands at the gate would also be able to see who is booked on a flight, record a disruption and rebook, which a gate agent has no need for.

## Decision

**A passenger boards once, and a new role can do only that.**

- **`POST /api/v1/ops/boarding-passes/boardings`** with `{barcode}`. The pass is checked exactly as in ADR-0056; if it is good, the passenger **boards**. The answer is the same shape as the check: `valid: true` means boarded now, with the pass; otherwise the reason, and nothing about the passenger.
- **A passenger boards once per flight.** The first scan is the one that counts. A second scan of the same pass is refused with `ALREADY_BOARDED`, and who made the first scan is kept (from their staff token). The primary key `(booking, flight, passenger)` is what decides a tie when two gates scan the same pass at the same instant, not the earlier check.
- **Checking a pass never boards anyone.** `verifyBoardingPass` is read-only; it now also says `ALREADY_BOARDED` for someone who has.
- **A passenger who has boarded cannot check out** (409 "Already boarded", naming them). Nobody is removed if any of those asked for has boarded, so a party cannot be half checked out by accident. In real life the check-in window has closed long before boarding, so this only matters when the window is wide.
- **A new role, `GATE`:** it may check a pass and board a passenger, and nothing else. It cannot see who is booked on a flight, record a disruption, rebook or create staff. Operations and administrators may board too. An administrator creates a gate agent account like any other staff account. The database constraint on roles now allows `GATE`.
- Boarding is its own table and its own port (`BoardedPassengers`), so scanning a pass never touches the booking or the check-in rows.
- Counts: `skyhub_boarding_pass_boardings_total{outcome}` (`boarded`, or the reason).

## Consequences

- Positive: a pass can be used once; a second use is visible and attributed to the first scan.
- Positive: the person at the gate holds a token that can do almost nothing if it is lost.
- Negative: **there is no time rule on boarding.** A good pass can be boarded at any time before departure and after; real gates open and close boarding, and this does not. It is the next thing to decide, with the flight's actual boarding times.
- Negative: **boarding cannot be undone.** A wrong scan has no correction short of the database. An "un-board" would need its own authority.
- Negative: **it needs the gate to be online**, like the check (ADR-0056).
- Negative: the ops website was built for operations staff. It turned out that it could not even hold the `GATE` role: its session parser rejected it, so a gate agent was treated as signed out. ADR-0062 fixes that and adds the gate screen.
- Not done: boarding by a standby or a passenger without a pass, and boarding more than one segment of a trip in one scan.
