# ADR-0025: Cancelled flights are not sold, and search follows disruptions

**Status:** Accepted
**Date:** 2026-10-30

## Context

Disruptions (ADR-0014) were recorded and shown to travellers who already held a booking, used by check-in, refunds and rebooking. Nothing stopped the *next* traveller: search still offered a cancelled flight, and booking, holds and quotes accepted it and took payment. That is the worst place for the gap, because it costs a traveller money for a flight that will not fly.

Two different problems, with different answers:

- **Booking owns the disruption data**, so it can refuse to sell a cancelled flight directly and immediately.
- **Search does not**, and it must stay fast: it works from an in-memory timetable and touches no database or network per search (ADR-0006). It cannot ask booking about every flight on every search.

## Options considered

For search to learn about disruptions:
1. **Ask the booking service during each search** — a network call per flight per search, and search becomes only as available as booking.
2. **Search reads booking's database** — breaks database-per-service (ADR-0003).
3. **Poll a booking endpoint on a timer** — simple, but always stale by the interval, and each instance does the same work.
4. **Booking publishes flight state to Kafka; search keeps it in memory** — updates in seconds, no calls during a search, and nothing shared.

For what search keeps and how it recovers after a restart:
- **A normal topic with a 7-day retention** — a search instance started later would forget a cancellation recorded two weeks ago for a flight next month.
- **A compacted topic** — Kafka keeps the latest event per key indefinitely.

## Decision

- **Booking refuses to sell a cancelled flight.** One small class, `FlightSalesGuard`, is called by pricing (which covers quotes and bookings, and runs before any charge) and by holds. One lookup per distinct flight of the trip, made before anything is charged or locked, answered with a 409 "Flight cancelled". A delayed flight is still sold: a traveller can choose it knowing the new time. The seat map of a cancelled flight can still be viewed, for people holding a booking on it.
- **Booking publishes the state of a flight** to a topic, `skyhub.flight.disruptions`, through the same transactional outbox as booking events (ADR-0019), inside the transaction that records the disruption. Each outbox event now names its own topic. Repeating the same request writes nothing, so a retrying tool is quiet. The event holds no personal data.
- **The topic is compacted and keyed by flight and date.** Kafka keeps the latest event for each key forever, so the topic is a durable snapshot of the current state, not a log that expires.
- **Search keeps the state in memory.** A hash map keyed by flight and date gives O(1) lookups, so a search is as fast as before. A cancelled flight is left out of results (including as either leg of a connection); a delayed flight is shown with its new time beside the schedule; looking a flight up by number still finds a cancelled one, marked cancelled, because booking needs to see it.
- **Two choices make in-memory state correct.** Every instance reads every message (its consumer group is unique to the instance, so instances do not split the work and each ends with the whole picture), and every start reads from the beginning of the topic, so a new instance learns every disrupted flight however old.
- **Safe under concurrency and bounded in size.** The Kafka thread writes while request threads read, so the state is a concurrent map, and a report replaces only an older one (a replay or late message cannot undo a newer one). Flights that left more than a day ago are not kept, and an hourly purge drops them, so memory does not grow with time.
- **A message that cannot be read is skipped and counted,** never retried, so one bad message cannot block the ones behind it. An alert fires on it.
- **Disruptions recorded before this existed** reach search through a one-off, opt-in backfill (`skyhub.outbox.backfill-flight-events=true` for one start), which republishes current disruptions. Republishing is harmless because the topic is compacted and consumers apply only what is newer.
- **The shape of the event is a shared example** read by the tests of both services, as with the other events.

## Consequences

- Positive: a traveller cannot buy, hold or be quoted a cancelled flight, and is not charged for one.
- Positive: search stays fast, needs no new infrastructure beyond the broker, and recovers fully after a restart (tested live).
- Positive: the agent's alternatives, which come from search, stop including cancelled flights without any change to it.
- Negative: **search is eventually consistent.** For a few seconds after staff record a cancellation, search may still offer the flight. That is acceptable only because booking now refuses the sale: search is advice, booking is the guarantee.
- Negative: **if search cannot reach Kafka, it fails open.** A search instance that starts while the broker is down shows every flight as scheduled until it connects. The booking guard still protects the money.
- Negative: **connections are judged on scheduled times.** A delay on the first leg of a connection can make the connection impossible, and search does not re-check it (it shows the delay beside the leg). Re-validating connections with revised times is the follow-up.
- Negative: a delayed flight still sorts by its scheduled time.
- Negative: a consumer group per instance means a new group each start. They hold no committed offsets, so Kafka removes them quickly, but they add noise.
- Follow-up: re-validate connections for delays; a way to withdraw a disruption (there is none today, so this is also a limit of ADR-0014); show cancellations to travellers who search for a route and find nothing.
