# ADR-0069: Moving travellers who are checked in

**Status:** Accepted
**Date:** 2026-11-03

## Context

When a flight is cancelled or delayed, staff move the bookings on it to another flight (ADR-0023). One rule refused any booking that was **checked in** on the flight: its boarding passes name seats that will not exist, so ADR-0023 left it for later. ADR-0055 gave the traveller a way out, checking out, but a traveller who has not seen the email, or is not at a screen, stays stuck, and the staff member cannot move them. After a cancellation the people most likely to be stuck are exactly the ones who were most organised. The disruption agent proposes these bookings like any other (it does not look at check-in), so their rows on the operations page came back "refused" one by one.

## Options considered

1. **Keep refusing, and ask staff to check each traveller out first.** There is no staff check-out, and it would be a second step for every one of them.
2. **Move the booking and leave the check-in alone.** Rejected: a check-in row is keyed by the flight's *place on the booking* (booking, flight index, passenger), not by the flight. After the move the same row would read as a check-in for the **new** flight, with a boarding number issued for the old one, and the old passes would look current to anyone who read only the rows.
3. **Move the booking and check the whole party out of the old flight in the same transaction.** Chosen.

## Decision

- **A checked-in booking can be moved.** The rule `MustNotBeCheckedIn` is replaced by `MustNotHaveBoarded`: a booking is refused only when **a passenger has already boarded** that flight ("A passenger has already boarded SK501, so the booking is not moved"). Someone on the aircraft is not moved. Every other rule (the flight must be disrupted, the route, the connections) is unchanged.
- **The move checks the party out in the same transaction.** After the booking, the seats, the audit row and the outbox event are written, `RebookingService` deletes the check-in rows of that flight of that booking. Either the whole move happens or none of it does. The party is checked out as a whole even if only one passenger was checked in (ADR-0058), because the party moves together.
- **The old passes stop working at once:** the flight is no longer on the booking at that place, so a scan answers `NOT_CHECKED_IN` (ADR-0056). The new flight starts not checked in; the traveller checks in again, and the check-in-open reminder (ADR-0029) is sent for it in the usual way. The rebooking email (ADR-0023) is unchanged and does not mention check-in.
- **The database now says a boarded passenger is a checked-in passenger** (migration V24): `boarded_passenger` has a foreign key to `check_in` on `(locator, segment_index, passenger_index)`, `ON DELETE RESTRICT`. This closes a race the service cannot: a gate scanning a pass and a move checking the same party out at the same instant. Without it the scan could insert a boarding after the move's check, and, being keyed by position, that boarding would read as boarding the new flight. With it, one of the two loses: the move's delete fails and the move stops with "A passenger has just boarded the flight, so the booking is not moved" and nothing changed. It also makes ADR-0060's "a boarded passenger cannot check out" true in the database, not only in the service.
- **Efficiency:** one extra `EXISTS` on a primary-key prefix (is anyone boarded on this flight of this booking) and one delete by the check-in table's unique key, per move. Nothing is added per passenger; a bulk move of 50 (ADR-0035) adds up to 50 of each.
- **Operability:** a counter, `skyhub_rebooking_check_ins_released_total`, counts passengers checked out by a move, and only appears once there is one. No alert: it is a normal result of a deliberate staff action, and a refused move already counts under `skyhub_rebookings_total{outcome="refused"}`. A runbook entry says what to do when a move is refused for a boarded passenger.

## Consequences

- Positive: staff can move everyone on a cancelled flight, including those who checked in, in the same batch, with no extra step and no database access.
- Positive: the boarding invariant is now enforced where it cannot be raced.
- Negative: **the traveller is not told to check in again.** The rebooking email names the new flight and seats only, and the website shows the new flight as not checked in. The check-in reminder (ADR-0029) is what brings them back, and it is sent when check-in opens for the new flight, which may be much later than the move. Adding a line to the email would change the event's shape and its shared example.
- Negative: **a pass that was already issued is not revoked at the traveller's end.** It stays on their phone; the gate check (ADR-0056) refuses it, as it does after a check-out.
- Negative: **boarding numbers already given on the old flight are not reused**, so the old flight's sequence has gaps (as after a check-out, ADR-0055).
- Negative: **the check-out is not recorded as a row.** The rebooking audit row (who moved, from, to) is the record; the number checked out is a counter and a log line only.
- Negative: **a move that races a gate fails rather than waits.** The staff member sees a refusal and tries again; by then the passenger has boarded and the move is refused for that reason.
- Not done: moving a booking only for the passengers who have not boarded (it is the whole party or none, as everywhere), telling the traveller to check in again, and a staff screen of who is checked in.

## Verification

Unit tests for the rule (boarded refused; checked in alone is not; the defaults list the new rule and not the old) and for the service (a checked-in party is moved and checked out; a boarded passenger is refused and nothing is changed; nothing is touched when the booking was not checked in or does not move; a passenger boarding at the very moment is refused, not an error). Integration tests on real PostgreSQL through the real filter chain: a checked-in party moved off a cancelled flight, its old pass refused with `NOT_CHECKED_IN`, the new flight not checked in and checked in again with a new pass; a party with one checked-in passenger moved and checked out; a boarded passenger refused with the booking, seats and check-ins as they were; and the database refusing to check out a boarded passenger or to board one who is not checked in. The full booking build (997 tests) passed with the new migration under every integration test. The live check was **not** run, nothing was clicked through in a browser, and the gate-versus-move race was reasoned about and covered by the constraint, not reproduced with two real concurrent requests.
