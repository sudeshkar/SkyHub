# ADR-0007: REST for queries, events for side effects

**Status:** Accepted. Both halves are now built: events arrived with notifications (ADR-0019).
**Date:** 2026-10-30

## Context

Some work the user waits for (search, book). Other work they do not (email, loyalty miles). If a slow or failing email service could fail a booking, availability would be worse than that of the weakest dependency.

## Options considered

1. **Call every service synchronously from booking** — simple, but one failure breaks the booking.
2. **REST for what the user waits for; events for what they do not** — booking publishes "booking confirmed" and other services react in their own time.

## Decision

Option 2. Searches, bookings, holds, check-in and payments are REST calls. Side effects (notifications, miles) will be driven by events through Kafka. To make a booking and its event succeed or fail together, the event is written to an outbox table in the same transaction and published afterwards; consumers must be idempotent because delivery is at least once.

## Consequences

- Positive: a failing email service never fails a booking.
- Negative: eventual consistency between booking and its consumers; an outbox, a broker and idempotent consumers to build and operate.
- Today: Kafka runs in `docker compose`, and the booking-confirmed email is the first event (ADR-0019). Other side effects (cancellation, disruptions, check-in) are still to come.
