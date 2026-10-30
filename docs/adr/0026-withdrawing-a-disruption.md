# ADR-0026: Withdrawing a disruption

**Status:** Accepted
**Date:** 2026-10-30

## Context

Recording a disruption (ADR-0014) is idempotent and can be corrected, but it cannot be taken back. A cancellation entered against the wrong flight, or a delay that resolved itself, stays on record: the flight stays unsellable (ADR-0025), search keeps hiding it, and travellers see a notice that is no longer true. Operations had no way to fix this short of editing the database, which is exactly what a staff screen is meant to avoid.

Withdrawing is not the mirror image of recording, because other features have already used the record:

- **Refunds.** A traveller moved off an airline-cancelled flight keeps the full refund (ADR-0023). That rule reads the disruption record, so deleting the record after a rebooking would quietly take the refund right away.
- **Search.** Its in-memory state is rebuilt from a *compacted* topic (ADR-0025). If a withdrawal only deleted the database row, the last event for that flight would stay on the topic for ever, and every search instance that restarted would learn a cancellation that no longer exists.
- **Emails.** Travellers may already have been told.

## Options considered

For what a withdrawal looks like to search:
1. **Publish nothing; search forgets on its own** — it never would: the compacted topic keeps the cancellation indefinitely.
2. **Publish a tombstone (a null value)** — compaction removes the key eventually, but a service that is replaying cannot tell "withdrawn" from "never disrupted", and a late older event could bring the cancellation back.
3. **Publish an ordinary event saying the flight is cleared, on the same key** — it replaces the disruption on the topic, carries a timestamp like every other event, and is applied by the same newest-wins rule.

For a flight that already has rebookings:
1. **Delete anyway and rely on the audit table** — the refund rule would have to be rewritten to read the audit table, and the traveller's right would depend on two tables agreeing.
2. **Refuse** — simple and safe: once people have been moved, the cancellation is a fact other decisions rest on.

## Decision

- **`DELETE /api/v1/ops/disruptions/{flightNumber}/{departureDate}`**, for the same staff roles that may record a disruption. It returns how many active bookings are on the flight, like recording does.
- **It is refused with 409 once any booking has been moved off the flight.** The answer says why. The check is `existsByFromFlightAndFromDate`, backed by a new index (migration V13), so it stops at the first row however many rebookings exist.
- **With nothing recorded it answers 404.** Every request that finds a record deletes it, so repeating a request cannot do anything the first did not.
- **The deletion and the event are one transaction** (the outbox, ADR-0019). Either both happen or neither does, so search cannot be told about a withdrawal that did not happen, or miss one that did.
- **The event is the same `flight.disrupted` event with `"disruption": "CLEARED"`**, on the same key `FLIGHT@DATE`. Compaction keeps it and drops the cancellation. Search stores it as "scheduled" with its timestamp, so a late older cancellation is ignored. The entry is dropped by the existing purge a day after the flight leaves, like any other. The shape is `api-specs/events/flight-cleared.v1.example.json`, read by the search tests.
- **Bookkeeping:** a counter `skyhub_disruptions_withdrawn_total` and a log line naming the staff member. No alert: like recording, it is a deliberate action by a person.
- **The operations page** has a separate "Withdraw a disruption" form with a confirmation tick. It is a separate component because it is rarer and asks for something the recording form does not.

## Consequences

- Positive: a mistaken cancellation can be fixed in seconds, by the person who made it, and the flight is sold and searched again.
- Positive: nothing about the refund rule changes, and it cannot be broken by a withdrawal.
- Positive: a search instance that restarts later learns the correct state from the compacted topic.
- Negative: **travellers are not told.** The "your flight is disrupted" email already sent cannot be recalled, and no "the disruption was withdrawn" email is sent. Their booking page stops showing the notice. A withdrawal email is one event type and one email template (the same recipe as ADR-0020), and is the follow-up.
- Negative: **a flight with rebookings can never be un-cancelled** from the screen. That is the point of the guard, but a genuinely mistaken cancellation that was already acted on needs a person to decide what to do about the moved travellers.
- Negative: check-in or refunds that happened *while* the flight was cancelled are not undone.
- Follow-up: the withdrawal email; showing the history of a flight's disruptions (today only the current record exists).
