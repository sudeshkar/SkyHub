# ADR-0004: Store schedules, derive dated flights

**Status:** Accepted
**Date:** 2026-10-30

## Context

An airline flies the same flight on the same weekdays for months. A search is always for a date. We must decide whether to store every dated flight or only the pattern.

## Options considered

1. **Store every dated flight** — one row per flight per day. Simple queries, but the table grows by hundreds of rows a day, and a schedule change means rewriting future rows.
2. **Store the schedule, derive the dated flight** — one `flight_schedule` row (flight number, route, days of the week, departure time in UTC, duration). The flight for a given date is computed when needed.

## Decision

Option 2. `FlightSchedule` holds the pattern; a dated flight is built from the schedule and the requested date (departure = date + `departureTimeUtc`, arrival = departure + duration). The timetable is a few thousand rows, so it stays in memory (ADR-0006).

## Consequences

- Positive: tiny table; a schedule change takes effect for all future dates at once.
- Negative: one-off changes for a single date (delay, cancellation) cannot live in the schedule. They are recorded separately as disruptions (ADR-0014) and applied on top.
- Negative: every search computes its legs, which is cheap in memory but is work a stored table would not do.
