# ADR-0063: The boarding window

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0060 let a gate board a passenger once, and named its own gap: *there is no time rule on boarding.* A good pass could be boarded a day early or after the flight had gone. Real gates open and close boarding some time before departure, and a delayed flight moves both times.

## Options considered

1. **Check the time inside `BoardingPassVerifier`.** Rejected: checking a pass is read-only and must keep answering "is this pass good?" at any time (ADR-0056). A staff member asking about a pass at the desk three hours early should still get "valid".
2. **A time rule inside `BoardingService` with inline arithmetic.** Rejected: the open and close distances would be untestable constants and the next rule that needs "when will this flight really leave?" would copy the lookup.
3. **A small domain rule plus one place that answers "when will it really leave?".** Chosen.

## Decision

- **`BoardingWindow(opensBefore, closesBefore)`** in the domain: a pure record with `stateAt(departure, now)` returning `NOT_YET_OPEN`, `OPEN` or `CLOSED`. Opening is inclusive, closing is exclusive, and it refuses to be built with a close that is not after the open or is after departure. Defaults: **opens 45 minutes before departure, closes 10 minutes before** (`skyhub.booking.boarding.opens-before`, `skyhub.booking.boarding.closes-before`).
- **`ExpectedDepartures.of(flight, date)`** in the application layer: a recorded delay's revised time beats the timetable (ADR-0014); with none, the flight catalogue is asked. A flight that does not operate is `FlightNotFoundException`. It is not asked at all when a delay exists.
- **`BoardingService.board`** checks the pass first (ADR-0056), and only a good pass reaches the time rule, so the catalogue is asked at most once per scan and never for rubbish text. Outside the window the answer is `valid: false` with the new reason **`BOARDING_NOT_OPEN`** or **`BOARDING_CLOSED`**, nothing is written, and the gate sees no information about the passenger.
- **Order of reasons:** a pass that was already used still says `ALREADY_BOARDED` (the verifier decides that first), whatever the time.
- **Checking a pass has no clock.** `verifyBoardingPass` never returns the two new reasons.
- The existing counter `skyhub_boarding_pass_boardings_total{outcome}` gains the outcomes `boarding_not_open` and `boarding_closed`; no new metric.
- **Website:** the gate screen tells the agent in plain words ("Do not board them yet; try again when boarding opens", "Boarding has closed ... send the passenger to the desk").
- Contract: the two reasons are added to the `BoardingPassVerification` reason enum; `boardPassenger` documents the window.

## Consequences

- Positive: a gate can no longer board a passenger a day early or after the doors are shut, and a delay moves the window by itself.
- Positive: the lookup of a flight's real departure time lives in one class. The next rule that depends on it (for example, notifying a late passenger) reuses it.
- Positive: a sound rule can be changed by configuration; the live check widens it (`PT2400H`) for the seed flights, as it already does for check-in.
- Negative: **`CheckInService` still has its own copy of the "disruption beats the timetable" lookup.** It also records metrics and throws for a cancelled flight, so it was left alone in this slice; moving it onto `ExpectedDepartures` is a small follow-up.
- Negative: **one window for every flight.** A wide-body to New York would board earlier than a short hop; a per-route window would be a new `BoardingWindow` chosen by flight, not a change to this class.
- Negative: **a closed window cannot be overridden.** A late passenger the gate agent wishes to let on cannot be, short of the database. An override would need its own role and an audit trail.
- Negative: **it needs the catalogue.** If flight-search is down, boarding answers with the existing "timetable unavailable" error instead of guessing.
- Not done: boarding times shown to travellers on the boarding pass, and an undo of a wrong scan (still listed under "Check-in extras").

## Verification

Unit tests for the window, the departure lookup and the service (open, early, closed, delayed, already boarded, checking has no clock); an integration test on real PostgreSQL through the real filter chain with the default window; the existing gate integration tests with the window opened wide. The live check was **not** run for this slice, and the gate screen was **not** clicked through in a browser.
