# ADR-0023: Staff move bookings off a disrupted flight, and the service checks the rules again

**Status:** Accepted
**Date:** 2026-10-30

## Context

The disruption agent (ADR-0022) proposes where affected bookings could go. Something has to carry a proposal out. Moving a booking changes seats on two flights, the booking itself, and what a traveller is told, and it can strand someone if it is wrong. It also interacts with the refund rules (ADR-0016), which until now looked only at the flights currently on a booking.

## Options considered

1. **Let the agent call a "rebook" tool directly** — fast, and any model connected to that tool could then change real bookings with no person involved. Rejected.
2. **Staff rebook by hand from a list the agent produced** — safe, but there is no endpoint to do it with, and repeating a risky multi-step change by hand invites mistakes.
3. **A staff-only booking endpoint that does the whole move in one transaction and re-checks every rule itself; the agent's CLI calls it only after a person says yes to each booking.**

## Decision

Option 3.

- **The booking service never trusts whoever proposed the move.** `POST /api/v1/ops/pnrs/{locator}/rebooking` (staff token, either role) re-runs every rule even though the agent's guardrails already did. The rules are small classes behind one interface and run by the service, so a new restriction is a new class listed in `RebookingRules.defaults()`, with nothing existing edited (open/closed). Today: the flight being left must have a recorded delay or cancellation; the booking must not be checked in on it; the new flight must not be cancelled; it must fly the **same route** (and if a route cannot be checked the move is refused); it must not leave before the original was due. The booking itself must exist and be active, and the new flight must operate.
- **All or nothing.** One transaction covers the booking, the seats on both flights, the audit row and the outbox event. All seats for the whole party are chosen before any is touched; new seats are booked, flushed (so a concurrent booking of the same seat fails here), and only then are old seats released. A party that does not fit changes nothing.
- **Seats are chosen by a strategy that keeps a party together.** `PartyTogetherAllocator` prefers adjacent seats in one row, then the same row, then the nearest seats from the front, separately for each cabin the party sat in. It sorts the free seats once and slides a window over each row: O(n log n), not a search per passenger, so a full wide-body cabin costs the same as a small one (a 12,000-seat cabin is tested). Another way of choosing is another `SeatAllocator` bean.
- **Asking twice is safe.** A booking already on the requested flight answers `changed: false`, writes no second audit row and sends no second email.
- **Audit.** A `rebooking` row records who (from the verified token, never from the request), which booking, from which flight and date, to which, and when. It holds no names or contact details.
- **The traveller is told.** A `booking.rebooked` event goes into the outbox in the same transaction (ADR-0019) and the notification service emails the new flight and each passenger's seat. The email makes no promise about money, because what is refundable depends on why the flight was disrupted.
- **A move must not cost the traveller their refund.** ADR-0016 gives a full refund when the airline cancelled a flight *on the booking*. Moving a traveller off a cancelled flight takes that flight off the booking, so without care they would lose the right the airline's own cancellation gave them. When the fare rules say "no refund", the refund decision now also checks whether the booking was moved off a flight that was cancelled (not merely delayed) and, if so, refunds in full. The common path (plenty of notice) costs no extra query.
- **Carrying out proposals needs a person, one booking at a time.** The agent's CLI `--apply` shows each proposed move and asks: yes, no, yes to all the rest, or quit. An empty or unclear answer is "no". There is no unattended flag, and the CLI refuses to run it without a terminal. Moving is **not** an MCP tool: the staff token lives in the CLI process that asked the person, not in the tool server a model talks to.
- **The leg to move comes from the booking, not the model.** The guardrails fill it in on every accepted proposal; a proposal that never passed them has none, and the applier skips it.

## Consequences

- Positive: a wrong proposal, a buggy agent or a hurried person cannot strand a traveller: the service refuses unsafe moves, and a refused move changes nothing.
- Positive: every move is attributable, and the traveller learns of it by email.
- Positive: the traveller keeps the refund right the airline's cancellation gave them.
- Negative: **the service cannot check connections.** The flight catalogue it asks knows when a flight leaves and its route, not when it lands, so the service does not verify that the new flight arrives in time for the traveller's next flight. The agent's guardrails do (it knows arrival times), but a person using the endpoint by other means is trusted on that point. Adding the arrival time to the catalogue and a rule for it is the follow-up.
- Negative: a booking checked in on the disrupted flight is refused, not moved. Those travellers have boarding passes for seats that will not exist; moving them means invalidating the check-in, which is a decision of its own.
- Negative: one leg of one booking per call, no price difference or fee (a rebooking after a disruption is free and the fare is unchanged), and the replacement's cabin is the traveller's own cabin or the move fails; a party is never upgraded or downgraded silently.
- Negative: the CLI applies one booking at a time, sequentially. For hundreds of bookings that is hundreds of confirmations, or one "all the rest"; a screen with bulk approval is the follow-up.
- Follow-up: arrival times and a connection rule; moving checked-in travellers; an approval screen on the operations page; a refund-completed email.
