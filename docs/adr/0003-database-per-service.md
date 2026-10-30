# ADR-0003: Database per service

**Status:** Accepted
**Date:** 2026-10-30

## Context

SkyHub has two services with very different loads: flight-search (read-heavy, about 100 searches per booking) and booking (writes, must be strongly consistent). They need to scale, fail and change their schema independently.

## Options considered

1. **One shared database** — simple, allows joins across services. Any schema change can break the other service, and a slow booking query can slow search.
2. **One database per service** — each service owns its data; others reach it only through its API.

## Decision

Option 2. flight-search uses the `flightsearch` database and booking uses the `booking` database (`DB_URL` in each service's `application.yml`). Booking never reads flight-search tables. It asks flight-search over REST for the flight facts it needs (`FlightCatalog`), and keeps only what it must (flight number, date, times) on its own segments.

## Consequences

- Positive: search keeps working when booking or its database is down; each service migrates its schema alone.
- Negative: no cross-service joins or transactions. Data that crosses services is eventually consistent (a flight changed in flight-search is seen by booking on its next lookup).
- Negative: more databases to run and back up. Locally one PostgreSQL server hosts both.
