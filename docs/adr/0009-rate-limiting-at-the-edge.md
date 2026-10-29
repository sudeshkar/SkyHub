# ADR-0009: Rate limiting at the edge with a sliding window counter

**Status:** Accepted
**Date:** 2026-10-29

## Context

Search, booking and booking lookup need protection from floods and from brute-force guessing of booking references. The Java services only see requests from the Next.js server, never from end users.

## Options considered

1. **Per-IP limits inside the Java services** — every request appears to come from the Next.js server, so all users would share one budget.
2. **Fixed window counter** — O(1) memory, but allows twice the limit across a window boundary.
3. **Sliding log** — exact, but memory grows with every request.
4. **Sliding window counter in Redis at the Next.js server** — O(1) memory (two counters per client), close to exact, shared across instances.

## Decision

Option 4. `proxy.ts` limits search page requests; Server Actions limit booking and lookup. Limits are counted once per request, never during render (render can run more than once — an early version double-counted). Client IPs are hashed before use as keys, and `X-Forwarded-For` is trusted only behind our own load balancer. If Redis is slow (over 300 ms) or down, requests are allowed: availability wins, and the backend still protects itself.

## Consequences

- Positive: accurate limits with constant memory, shared across Next.js instances.
- Positive: no raw IP addresses stored.
- Negative: fail-open means no limiting during a Redis outage.
- Follow-up: move edge limits to the Kong gateway when it is introduced; keep action limits as defence in depth.
