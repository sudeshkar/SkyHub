# ADR-0027: Connections follow delays

**Status:** Accepted
**Date:** 2026-10-30

## Context

Search shows a delay beside a flight (ADR-0025) but still judged one-stop connections on scheduled times. A first flight delayed by two hours could be offered with an onward flight it can no longer catch, and a connection that a delay had made comfortable was never offered. The traveller finds out at the airport.

## Decision

- A leg reports **expected** times: the revised departure if it is delayed, and an arrival moved by the same amount (a delay is assumed to be carried through the flight). The scheduled times are never changed.
- The connection rules (minimum connection time, airport transfer, maximum layover) are applied to the **expected** inbound arrival and outbound departure. The layover shown is the real one.
- It is one change in one place: the layover calculation. No new class was needed, because the status board already supplies the revised time to every leg.
- Cost is unchanged: still a hash join, with two subtractions per candidate.

## Consequences

- Positive: a connection is offered only if it can be made on the times we know, and a delay that makes one possible is not hidden.
- Negative: **a delay is assumed to last the whole flight.** A crew may make up time or lose more. We only know the departure.
- Negative: onward flights are looked for on the arrival day and the next. A delay of more than a day is not searched for.
- Negative: results are still sorted by scheduled departure.
- Negative: a traveller who already holds a booking is not re-checked; this changes what search offers, not what has been sold.
