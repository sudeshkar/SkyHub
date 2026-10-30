# ADR-0008: `Idempotency-Key` on `POST /pnrs`

**Status:** Accepted
**Date:** 2026-10-30

## Context

A client that times out cannot tell whether its booking was made. Retrying blindly can create a second booking, and once payments exist (ADR-0015), charge twice.

## Options considered

1. **No protection** — duplicates on retry.
2. **Detect duplicates by content** (same passengers, same flight) — blocks legitimate repeat bookings.
3. **A client-supplied `Idempotency-Key` header** — the client decides what "the same request" means.

## Decision

Option 3. `POST /pnrs` requires an `Idempotency-Key`. Booking stores the key as a SHA-256 hash (never in clear) on the PNR, together with a fingerprint of the request body. The same key and same body returns the original PNR with 200 instead of 201 (`BookingResult.created` is false). The same key with a different body is rejected with 422 (`IdempotencyKeyReusedException`). The key is unique in the database, so two concurrent requests with one key cannot both create a PNR. The payment charge key is derived from it, so a retry never charges twice.

## Consequences

- Positive: safe retries for clients and for the website's server action.
- Negative: clients must generate and keep a key per booking attempt; keys are stored for the life of the PNR.
