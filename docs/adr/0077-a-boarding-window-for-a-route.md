# ADR-0077: A boarding window for a route

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0063 gave every flight the same boarding window, opening 45 minutes and closing 10 minutes before departure, and named the limit: *one window for every flight; a per-route window would be a new `BoardingWindow` chosen by flight, not a change to this class.* A wide-body to New York carries far more people and boards earlier than a short hop, and a gate that wants a quiet last stretch before a long-haul pushes back closes earlier. With one window an airline either opens late for the big aircraft or early for the small one.

## Options considered

1. **A window chosen by how long the flight is** (derived from departure and arrival). Rejected: it guesses at what an operator knows, the arrival is not always in the timetable (`FlightInfo.arrival` can be missing), and an operator could not say "this route, only".
2. **A window chosen by aircraft or flight number.** Rejected: flight numbers are many and change by season; the route is the stable thing an operator reasons about.
3. **A window per route, set in the settings, with the standard window for every route not named.** Chosen. It is the smallest thing that answers the question, it needs nothing the booking service does not already have (`FlightInfo` has the origin and destination), and an unknown route keeps today's behaviour.

## Decision

- **A route is an origin and a destination, in that order** (`DXB-JFK`; the flight back, `JFK-DXB`, is another route). Airport codes are matched in any case. A flight whose origin or destination is not known uses the standard window.
- **`BoardingWindows`** (domain) holds the standard `BoardingWindow` and the windows by route, and answers `forRoute(origin, destination)`. It is pure and immutable and looks nothing up. `BoardingWindow` itself, and its rules (it must close after it opens, and not after departure), are unchanged.
- **Set in the settings**, for example:

  ```yaml
  skyhub:
    booking:
      boarding:
        opens-before: PT45M     # the standard window (the defaults)
        closes-before: PT10M
        routes:
          DXB-JFK:
            opens-before: PT75M
            closes-before: PT20M
  ```

  A route may name only one end; the other keeps the standard value.
- **A mistake stops the service starting, naming the route:** a key that is not two three-letter airport codes (`DUBAI`), or a route whose window cannot work (it would close before it opens, or after departure). A boarding window that silently keeps a flight from ever boarding is worse than one that is refused at start-up.
- **`BoardingService` picks the window by the pass's flight**, for both the ordinary gate and the late override (ADR-0076): late boarding is "after this route's gate has closed, before it leaves". Checking a pass still has no clock.
- **Cost:** one more answer from the flight catalogue for each scan that reaches the window. The catalogue is cached, and a pass that is not good never gets that far. No new query on any other path.
- **No new metric, alert or runbook entry:** refusals already count by reason (`boarding_not_open`, `boarding_closed`).

## Consequences

- Positive: an operator can open boarding earlier for a big aircraft or a long route, and close it earlier for one that needs it, without a deploy of code.
- Positive: nothing changes for a route that is not named, and a wrong setting is caught at start-up, not at the gate.
- Negative: **it is a setting, not something staff can change on screen.** A change needs a configuration change and a restart.
- Negative: **a route is the only key.** Two flights on the same route with different needs (a morning and an evening aircraft) share a window. A key by flight number would be a further, narrower override.
- Negative: **the route comes from the timetable.** A flight whose timetable entry has no origin or destination quietly uses the standard window, which is the safe answer and can surprise someone who expected their override.
- Negative: **the environment-variable form of the setting is awkward** (a route key has a dash and capitals); it is meant for the configuration file.
- Negative: the check-in window (ADR-0013) is still one window for every flight. It has the same limit and is not changed here.
- Not done: a window by flight number, editing windows on the operations page, and a per-route check-in window.

## Verification

Unit tests for `BoardingWindows` (the route's window for that route and the standard for every other; the flight back is a different route; case does not matter; an unknown route or an unknown end gets the standard; keys that are not two airport codes are refused naming the problem), for reading the settings (the defaults; the standard window from its own settings; a route's window; a route that names only one end; a route whose window cannot work, and a key that is not an airport pair, each stopping start-up with the route named) and for the service (a route that opens earlier boards a passenger the standard gate would refuse; a route closes by its own closing time; a flight with no known route uses the standard window; a late boarding follows the route's closing time). An integration test on real PostgreSQL through the real filter chain, with the settings applied to a real application context: a flight on the named route boards at five hours out while a flight on another route is still refused as not yet open. The whole booking build ran clean with 1038 tests and no failures. The live check was **not** run.
