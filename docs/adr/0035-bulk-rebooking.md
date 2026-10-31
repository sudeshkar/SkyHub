# ADR-0035: Moving many bookings in one request

**Status:** Accepted
**Date:** 2026-10-31

## Context

Staff approve the agent's proposals on the operations page (ADR-0024). Each approved move is its own call to the booking service (ADR-0023), so approving a flight of 500 bookings is 500 calls, made one after another by the website in batches of 20. Every call pays a full round trip, a token check and a rate-limit check for one move. ADR-0023 and ADR-0024 both name a bulk endpoint as the follow-up. The risk is that "bulk" quietly weakens something single moves guarantee: that a move is all or nothing, that every rule is checked, that the audit names the person.

## Options considered

1. **One transaction for the whole request** - all moves or none. One refused booking would undo 49 good moves, and a long transaction would hold seat rows locked while it works through the rest. Staff would also have to retry the whole set to learn which booking was the problem.
2. **A new bulk path that moves seats in set-based SQL** - fastest, and a second implementation of every rule and of the seat choice, which would drift from the first.
3. **Run the existing single move for each item, each in its own transaction, and report each result** - the same rules and the same code, nothing new to keep in step; the only new thing is the loop and the report.

## Decision

- **Option 3.** `POST /api/v1/ops/rebookings` takes 1 to 50 moves (`locator`, `segmentIndex`, `flightNumber`, `departureDate`) and answers 200 with one result per move, in request order. A new `BulkRebookingService` calls `RebookingService.rebook` for each; that class is not changed.
- **Each move is all or nothing; the request is not.** Every move keeps its own transaction, rules, audit row and email event. A refused move changes nothing and the rest carry on. This is stated in the contract so a caller never assumes otherwise.
- **Results say why, per booking.** `outcome` is one of `MOVED`, `UNCHANGED` (already there), `REFUSED` (a rule; `detail` is the reason), `NOT_FOUND`, `CONFLICT` (the seats changed while moving; try again), `UNAVAILABLE` (the timetable did not answer), `FAILED` (an unexpected error) or `NOT_ATTEMPTED`. `seats` is set for `MOVED` and `UNCHANGED`. Nothing in a result holds a name or an address.
- **A broken dependency stops the run.** After `UNAVAILABLE` or `FAILED`, the remaining moves are not tried and are reported as `NOT_ATTEMPTED`, so a timetable outage is not hammered 50 times. What was already moved stays moved and is reported.
- **Bounded and unambiguous.** More than 50 moves, none, or the same booking and flight twice are refused with 400 before anything changes.
- **The person is the token's.** The audit names the staff member from the verified token (any staff role that may rebook), exactly as for one move.
- **The website sends one call per batch** of up to 50 instead of up to 20 sequential calls, and shows the same per-booking results.
- **Monitoring:** nothing new to add. Each move goes through the ordinary single move, so `skyhub_rebookings_total{outcome}` (rebooked, unchanged, refused) counts bulk moves too, and one log line per request carries the counts by outcome. No alert: a refused move is a rule working, and a stopped run is visible to the person who is watching the page.
- **Cost:** O(moves). Each move reads the target flight's seats once, and the timetable lookups are already cached. The loop is sequential on purpose: moves onto one flight compete for the same seats, and running them in parallel would turn most of them into conflicts.

## Consequences

- Positive: a 500-booking flight is 10 requests, not 500, with identical rules, audit and emails.
- Positive: one refused or conflicted booking never hides the others, and staff see exactly which need attention.
- Positive: no second implementation of the rules to keep in step.
- Negative: **a request can half succeed.** If the connection drops after the service has moved some bookings, the caller does not know which. Asking again is safe (a move already done answers `UNCHANGED`, with no second audit row or email), so the answer to "what happened" is to repeat the request.
- Negative: the request holds a connection while it works (up to 50 sequential moves), so it can be slow when each move is slow. The cap and the sequential loop trade throughput for predictability; parallelism by flight would be the next step.
- Negative: still one leg of one booking per move, and bookings that are checked in are still refused, not moved.
- Follow-up: grouping and filtering proposals for big flights; moving checked-in travellers; cohort grouping in the agent; arrival times and a connection rule in the booking service.
