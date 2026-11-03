# ADR-0065: Who has boarded a flight

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0064 let operations take a wrong boarding back, but the person doing it needs the booking reference and the flight's and passenger's place on the booking, and nothing in the system showed those. A boarded pass is refused with no details by design (ADR-0056), and the gate screen shows only the passenger it just boarded. Operations also had no way to see how a flight's boarding was going: how many were checked in, and how many were still to come.

## Options considered

1. **Show names and seats too.** Rejected: the screen needs references and places to act, and nothing more. Names are personal data (DEVELOPMENT.md section 4), and the existing "affected bookings" answer (ADR-0022) already shows the pattern of references without names.
2. **Let the gate agent see it.** Rejected: it lists booking references, and ADR-0060 keeps a gate agent out of "who is booked on a flight".
3. **Build it from the booking tables in the web layer, or load every booking on the flight and filter.** Rejected: per-booking work grows with the flight, and the web layer must not know the tables (ArchUnit).
4. **A read-only report port answered by two indexed queries, shown to operations and administrators.** Chosen.

## Decision

- **`GET /api/v1/ops/flights/{flightNumber}/departures/{departureDate}/boardings`** gives `checkedIn`, `boarded`, `waiting` and a list of boardings: booking reference, the flight's place on the booking, the passenger's place (both from 0, exactly what `undoBoarding` takes), who scanned and when, oldest first. **No names.** `Cache-Control: no-store`.
- **Authority: `OPS` and `ADMIN` only** (`StaffRole.mayViewFlightBoarding`, written out like the other rules). A gate agent, a traveller and no token are refused (403, 403, 401).
- **`FlightBoardingReport`** is a port in the application layer; `JdbcFlightBoardingReport` answers it. **`FlightBoarding`** holds the rule that `waiting` is `checkedIn - boarded` and never below zero, because the two counts are read a moment apart.
- **Efficiency.** Two queries, each starting from `ix_pnr_segment_date` (date, flight) and reaching the check-in or boarding rows through the booking's unique reference and those rows' primary keys. Work is proportional to the passengers on that one flight, at most the seats on the aircraft (about 850 on the largest), not to all boardings ever recorded; no query per passenger. The answer is not paged, because that bound is small; if the list ever had to cover far more, a keyset cursor like "Your bookings" (ADR-0038) is the next step.
- **A flight nobody has boarded** gives zeros and an empty list. The flight is not looked up, so asking about a flight that does not exist is also an empty answer, not an error.
- **Website:** a "Who has boarded" panel on `/ops`, shown to operations and administrators and not to a gate agent. It counts flights and passengers from one, as "Undo a boarding" does, so a row's numbers can be typed straight into it.
- **No metric and no alert.** It is a read that a person asks for; nothing needs a second person to act, and the existing request metrics cover it.

## Consequences

- Positive: operations can see how a flight's boarding stands, and can find the numbers that undoing a wrong scan needs (ADR-0064).
- Positive: the answer holds nothing that identifies a person, so it can be shown or passed to tools safely.
- Negative: **the counts are two reads, not one snapshot.** Between them a passenger can board or check out, so `checkedIn` and `boarded` can disagree by a moment's worth. `waiting` is clamped to zero for that reason.
- Negative: **the list is not paged**, and a booking that was cancelled after boarding still appears (the boarding happened; the record is not hidden).
- Negative: **there is no live update.** The panel shows the flight as of the moment it was asked, and the person presses the button again.
- Negative: **the row does not link to the undo form.** The person types the numbers across.
- Not done: names or seats on the list, a count of passengers who have not checked in, and a version a gate agent may see with counts only.

## Verification

Unit tests for the read model; controller tests with the real security rules (200 with and without boardings, 401/403 for nobody, a gate agent and a traveller, 400 for a bad flight number, `no-store`); integration tests on real PostgreSQL through the real filter chain (counts, another date left out, who scanned, the list following an undo, an empty flight, and the three refusals); website tests for the action and the panel. Full booking build, frontend tests, `tsc`, `eslint` and `next build` were run. The live check was **not** run, and the panel was **not** clicked through in a browser.
