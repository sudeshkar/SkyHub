# ADR-0014: Flight disruptions: delays and cancellations

**Status:** Accepted
**Date:** 2026-10-30

## Context

Flights are delayed and cancelled. Operations staff need to record that, and travellers who hold bookings on the flight need to see it and not be let into a check-in window computed from the old departure time. Today a flight's departure time comes only from the timetable, which says what is *scheduled*.

## Options considered

1. **Edit the timetable in flight-search** — a delay becomes the new schedule. The timetable is an in-memory index rebuilt from schedules (ADR-0006); a one-day exception does not belong in a weekly schedule, and booking would still need to learn of it.
2. **Event-driven: publish "flight delayed", let each service react** — the right shape at scale, but needs a broker we do not run yet, and one consumer (booking) does not justify it.
3. **Record the disruption in the booking service, which already owns bookings and check-in** — one table keyed by flight and date; the timetable stays the schedule, the disruption is the exception layered on top.

## Decision

Option 3.

- A `disruption` row per flight and date: type `DELAYED` (with the new departure time) or `CANCELLED`, plus a short reason and when it was recorded. Recording again for the same flight and date **replaces** the row, so a second delay or a correction is a repeat of the same request (idempotent).
- **Who may record one.** `POST /api/v1/ops/disruptions` needs an operations key in the `X-Ops-Key` header (`SKYHUB_OPS_KEY`, at least 256 bits, compared in constant time). It is a different credential from the traveller's manage token and is never given to the website. If no key is configured, the endpoint always answers 401. This stands in for real staff authentication (SSO and roles), which is the follow-up. **Superseded in part by ADR-0017:** the shared key is gone; recording needs a staff login and each record names who made it.
- **What travellers see.** `GET /api/v1/pnrs/{locator}/disruptions` (manage token) lists the disruptions on the flights of that booking. It is a separate call so the booking contract itself does not change.
- **Check-in follows the truth.** A delayed flight's check-in window is computed from the *revised* departure time; a cancelled flight cannot be checked in (409 "Flight cancelled"). Passes already issued are not recalled: they are still valid for the flight they were issued for.
- **Who is affected.** Recording a disruption returns the number of active bookings on that flight, so operations can see the size of it. Notifying those travellers (email, SMS) and re-booking them automatically are out of scope; the website tells a traveller what happened and lets them cancel the booking and search again.

## Consequences

- Positive: no new infrastructure; the timetable and its caches are untouched; the rule "disruption beats schedule" lives in one place (the check-in use case asks one question).
- Positive: recording is idempotent, so an operations tool can retry safely.
- Negative: only the booking service knows about disruptions. Search results still show the scheduled time; a delayed flight in search is a follow-up (flight-search would read them).
- Negative: a shared key is coarse. Anyone holding it can disrupt any flight, and there is no audit of *who*. Mitigation for now: the key is separate and server-side only, and every recording is logged with the flight and type.
- Follow-up: staff login with roles; notify affected travellers; automatic re-accommodation; show disruptions in search.
