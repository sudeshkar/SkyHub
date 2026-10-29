# SkyHub Runbooks

One section per alert in `observability/prometheus/alerts.yml`. Each says what the alert means, how to confirm it, and how to mitigate. Start every investigation from the **SkyHub overview** dashboard in Grafana, then follow a `traceId` from a log line into Tempo.

## SearchErrorRateHigh

**Means:** more than 1% of search requests returned 5xx for 5 minutes.
**Confirm:** Overview → *Error rate (5xx)*. In Loki: `{service="flight-search", level="ERROR"}`; open the `traceId` of a failing request.
**Likely causes:** timetable failed to load at startup (no snapshot), a bad deploy, out of memory.
**Mitigate:** roll back the last deploy; check *Flights in timetable* is not 0; restart one instance at a time.

## SearchLatencyHigh

**Means:** search p99 above 200 ms for 10 minutes (SLO breach).
**Confirm:** Overview → *Latency p99 by endpoint*; *JVM heap used* for GC pressure.
**Likely causes:** CPU saturation, long GC pauses, an unusually large timetable.
**Mitigate:** scale out (search is stateless and needs no database); raise heap if GC-bound.

## BookingErrorRateHigh

**Means:** more than 2% of `POST /pnrs` returned 5xx for 5 minutes. A 409 (seat taken) is *not* an error.
**Confirm:** Overview → *Bookings per minute by outcome* and *Database connections*. Loki: `{service="booking", level="ERROR"}`.
**Likely causes:** database down or slow, flight-search unreachable (returns 503), encryption key misconfigured.
**Mitigate:** check PostgreSQL health and connection pool; see *FlightCatalogUnavailable*; never rotate `SKYHUB_PII_KEY` without the rotation procedure (ADR-0010).

## TimetableStale

**Means:** flight-search has not reloaded its timetable for over 15 minutes. Searches still work from the last snapshot.
**Confirm:** Overview → *Timetable age*; Loki: `{service="flight-search"} |= "Timetable refresh failed"`.
**Likely causes:** flight-search cannot reach its database.
**Mitigate:** restore database connectivity; the next scheduled reload recovers automatically.

## DatabaseConnectionsWaiting

**Means:** requests are queueing for a database connection — the earliest overload signal.
**Confirm:** Overview → *Database connections* (waiting > 0) and rising latency.
**Likely causes:** traffic spike, slow queries, connection leak.
**Mitigate:** scale out the service; check slow queries in PostgreSQL; do not simply raise the pool size above what the database can serve.

## FlightCatalogUnavailable

**Means:** more than 5% of booking → flight-search calls fail. Flights that already have seat rows keep working; new flight dates return 503.
**Confirm:** Overview → *Booking → flight-search calls* (`unavailable`); is flight-search up (*ServiceDown*)?
**Mitigate:** restore flight-search; no booking data is at risk (nothing is created while the check fails).

## BookingReferenceGuessing

**Means:** more than 12 failed "open my booking" attempts per minute for 5 minutes. Someone may be guessing booking references with common surnames. Real users mistype occasionally; this volume is not them.
**Confirm:** Overview → *Manage actions* (`session_denied` climbing against `session_opened`); in Loki: `{service="booking"} |= "Manage session denied"`.
**Mitigate:** the Next.js server already limits lookups to 10 per 15 minutes per client, so a flood means many clients: tighten that limit or block the source at the gateway. No booking is exposed by failed attempts (a wrong name and an unknown reference look identical). If `session_opened` also rises abnormally, treat it as a possible compromise and check recent `Manage session opened` logs.

## ServiceDown

**Means:** Prometheus cannot scrape a service for 1 minute.
**Confirm:** `up` in Prometheus; the service's readiness endpoint on its management port (`:9081/actuator/health/readiness`, `:9082/...`).
**Mitigate:** restart the instance; check its last log lines in Loki for the startup failure (missing `SKYHUB_PII_KEY` stops booking from starting by design).
