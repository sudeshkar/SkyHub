# ADR-0005: All times in UTC

**Status:** Accepted
**Date:** 2026-10-30

## Context

Flights cross time zones, and the UAE has no daylight saving while most departure and arrival cities do. Mixing local times in storage leads to wrong durations, wrong connection checks and wrong check-in windows.

## Options considered

1. **Store local times with their zone** — natural for display, but every comparison needs a conversion and a daylight-saving rule.
2. **Store and compute in UTC; convert only for display.**

## Decision

Option 2. Schedules store `departure_time_utc`, the API returns UTC offsets, services take instants from an injected `Clock` (so tests control time), and durations, connection times, hold expiry, the check-in window and token lifetimes are all computed in UTC. The website converts to the airport's local time when it renders.

## Consequences

- Positive: arithmetic on times is always correct; tests are deterministic.
- Negative: the website must know each airport's time zone and convert; a missing conversion shows the wrong local time. Tests for the display layer are needed.
