# ADR-0002: Optimistic locking for seat inventory

**Status:** Accepted
**Date:** 2026-10-29

## Context

A seat on a flight on a date must never be sold to two passengers. Two customers can select the same seat at the same moment, and the service runs as several instances, so in-memory locks (`synchronized`) do not help.

Most seats see little contention. A few (exit rows, front of cabin, popular flights on sale days) see a lot.

## Options considered

1. **Pessimistic locking** (`SELECT ... FOR UPDATE`) — the second transaction waits for the first. Correct, but holds row locks, risks deadlocks when several seats are locked in different orders, and makes users wait.
2. **Optimistic locking** (`@Version` column) — no locks while reading. On update, `WHERE version = ?` matches zero rows if someone else changed the seat first, and the transaction fails.
3. **Distributed lock** (Redis) — adds a dependency and failure modes (lock expiry, network partitions) for no benefit over the database's own guarantees.

## Decision

Option 2. `SeatInventory` has a `@Version` column. `BookingService` flushes inside the transaction so conflicts surface there, and the API maps both `OptimisticLockingFailureException` and `SeatUnavailableException` to **409 Conflict**.

A unique constraint on `(flight_number, departure_date, seat_number)` guarantees one row per seat, so there is exactly one row to compete for.

## Consequences

- Positive: no waiting and no deadlocks; scales across instances.
- Positive: proven by a concurrency test — 10 threads, one seat, exactly one success.
- Negative: under heavy contention many requests fail and must retry with another seat. Acceptable, because the user is choosing a seat anyway.
- Follow-up: Phase 2 adds `HELD` seats with expiry for the payment window.
