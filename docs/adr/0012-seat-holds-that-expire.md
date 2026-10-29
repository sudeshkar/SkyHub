# ADR-0012: Seat holds that expire

**Status:** Accepted
**Date:** 2026-10-29

## Context

A seat is only reserved when the traveller presses "Confirm booking". Choosing seats, typing passenger names and reviewing takes minutes, and during that time anyone else can take the same seat. The traveller finds out at the very end ("seat just taken") and has to start the seat step again. Airlines solve this with a temporary hold.

## Options considered

1. **Book the seat immediately when it is chosen** — simple, but abandoned sessions would leave seats booked forever; every seat click would need cancelling.
2. **Hold seats in Redis with a TTL** — expiry is free, but the seat's real state would then live in two places (PostgreSQL and Redis) and could disagree after a crash.
3. **Hold seats in the seat table itself (`HELD`, with an owner and an expiry time)** — one source of truth, one transaction, and the existing optimistic locking already stops two people holding the same seat.

## Decision

Option 3.

- A seat has a third state, `HELD`, carrying `hold_id` (an unguessable UUID) and `held_until`.
- `POST /holds` holds **all requested seats or none** in one transaction for **10 minutes** (`skyhub.booking.hold-ttl`). `DELETE /holds/{holdId}` releases them and is safe to repeat. Knowing the hold ID is the permission to use it, the same idea as a capability link.
- `POST /pnrs` accepts an optional `holdId`. Every seat is then converted from `HELD` to `BOOKED` in the booking's own transaction; if any hold has expired or belongs to someone else, the whole booking is refused and nothing changes. Without a `holdId` the flow works as before.
- **Expiry is enforced two ways.** The conversion checks `held_until` itself, so an expired hold can never be used even if cleanup is late. A scheduled cleanup (every 30 seconds) frees expired holds with one SQL `UPDATE ... WHERE status = 'HELD' AND held_until < now()`. That statement is idempotent, so several instances running it at once is harmless, and it bumps the row version so it cannot overwrite a concurrent change.
- Seat maps show a held seat as unavailable to everyone, including a second tab of the person who holds it; the website knows its own seats from the hold.

## Consequences

- Positive: a traveller keeps the seats they chose while they fill in the form; abandoned sessions free themselves.
- Positive: no new infrastructure, and the concurrency guarantee is the one already proven by the 10-thread test.
- Negative: someone can hold seats without booking, so a determined script could block a flight. Holds are rate limited per client at the website, capped at 36 seats (9 passengers × 4 flights) and short-lived; per-client hold limits at the gateway would be the next step.
- Negative: a seat can appear held for up to 30 seconds after its hold has expired, until cleanup runs. It cannot be mis-sold: the booking path checks the expiry time itself.
- Follow-up: extend a hold when the traveller is still active; show a countdown (done in the website).
