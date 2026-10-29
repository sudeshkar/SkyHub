# ADR-0006: In-memory timetable index instead of a search result cache

**Status:** Accepted (replaces the planned "cache search results in Redis")
**Date:** 2026-10-29

## Context

Search traffic is roughly 100× booking traffic. The first version queried PostgreSQL on every search and cached results in Redis for 5 minutes. With many routes × dates most real searches are cache misses, and every miss cost two database queries.

The timetable itself is small: a few thousand schedules.

## Options considered

1. **Keep the Redis result cache** — helps only repeated identical searches; every hit still pays a network round trip and deserialisation.
2. **In-memory timetable index** — load all schedules into immutable hash maps (by origin, destination, flight number), refresh every 5 minutes, compute every search in memory.

## Measurements

50 connections, 20 s, after a warm-up run, searches spread over 8 routes × 100 years of dates so nearly every request misses any result cache:

| Setup | Requests/s | p50 | p99 | Failed |
|---|---|---|---|---|
| Database per search + Redis result cache | 1,558 | 92 ms | 360 ms | 214 |
| Redis result cache, **every request a hit** | 6,784 | 100 ms | 215 ms | — |
| **In-memory index, no result cache** | **9,085** | **2 ms** | **74 ms** | **0** |

The index computing every search beats Redis even when Redis answers every request.

## Decision

Option 2. `TimetableCache` loads the timetable at startup and reloads it every 5 minutes, publishing a new immutable `Timetable` through a volatile reference (copy-on-write): readers never lock and never see a half-built index; a failed reload keeps the previous snapshot. Connection search joins onward flights by departure airport (a hash join). The Redis result cache was removed from flight-search. Responses carry `Cache-Control: public, max-age=60` so browsers and CDNs can reuse them.

## Consequences

- Positive: 5.8× throughput, p50 from 92 ms to 2 ms, no database load from search.
- Positive: one less dependency for flight-search (no Redis).
- Negative: timetable changes take up to 5 minutes to appear; memory grows with the timetable (fine at thousands of rows; revisit at millions).
- Follow-up: publish a "timetable changed" event to reload immediately when schedules are edited.
