# ADR-0036: A rebooking must not break the traveller's connections

**Status:** Accepted
**Date:** 2026-10-31

## Context

ADR-0023 named a gap: the booking service checks that a replacement flight flies the same route and does not leave early, but not that it **lands in time for the traveller's next flight** (or leaves after their previous one lands). The disruption agent's guardrails check this because the agent knows arrival times; a person using the endpoint directly, or the bulk endpoint (ADR-0035), was trusted. A trip DXB to LHR to JFK moved onto a later DXB to LHR flight could be confirmed, emailed, and missed. The service never asked the timetable for arrival times.

## Options considered

1. **Leave it to the agent's guardrails** - the service is meant to trust no proposer (ADR-0023). Rejected: the rule belongs where the data changes.
2. **Check only the flight being moved** - cheap, and blind to the neighbours, which is where the problem is.
3. **Load the neighbouring flights of the booking and judge both joins with a new rule** - the flight search service already returns arrival times; the rules are small classes behind one interface (open/closed).

## Decision

- **Option 3.** `FlightInfo` gains the scheduled `arrival` (the flight-search answer already had it). A new rule, `MustKeepTheConnections`, is listed in `RebookingRules.defaults()`; no existing rule or rule test changes.
- **Both joins are judged on expected times.** Expected departure is the revised time if the flight is delayed; expected arrival is the scheduled arrival moved by the same amount (the assumption search already makes, ADR-0027).
  - The replacement's expected arrival plus the minimum connection time must not be later than the **next** flight's expected departure.
  - The **previous** flight's expected arrival plus the minimum connection time must not be later than the replacement's expected departure.
- **The minimum is 60 minutes,** the same figure the agent's planner uses, held in one constant in the rule.
- **A neighbour that is cancelled is not a constraint.** It has no time to meet, and that traveller is being moved off it anyway; refusing here would only block moving them in either order. A neighbour that is not in the timetable any more is skipped for the same reason.
- **If the replacement's own arrival is not known, the move is refused** and says so, rather than guessed. (The adapter always supplies it; this covers a caller that does not.)
- **The refusal says which join fails and by how much,** in times a person can check: for example "SK502 would land 14:40Z, which leaves 20 minutes before SK777 leaves at 15:00Z; at least 60 are needed".
- **The service loads the neighbours once per move** (at most two timetable lookups, both cached, and at most two disruption reads) into the context, so the rule stays a pure function of what it is given, like the others. A single-flight booking loads nothing extra.
- **Bulk rebooking gets it for free** (ADR-0035 runs the ordinary move for each).

## Consequences

- Positive: the service now refuses the move that would strand a traveller mid-trip, whoever asked for it, and says exactly why.
- Positive: nothing existing was edited except where data had to flow: the flight record gained a field, the context gained two optional neighbours, and the service loads them.
- Negative: **a delay is assumed to last the whole flight** (as in ADR-0027), so a crew that makes up time is judged more strictly than it will fly.
- Negative: **it judges the neighbours' times as the timetable and the status board know them now.** A neighbour delayed after the move is not re-checked; nothing re-judges a booking later.
- Negative: **no airport-transfer allowance.** The route rule keeps both ends the same airports, so a connection stays at one airport; a replacement that changes airport on a connecting leg is refused by that rule, not judged here.
- Negative: the 60 minutes is one figure for every airport and every traveller; it is not read from the timetable.
- Negative: a person who knows a tighter connection is fine cannot override it through the API. That is deliberate for now.
- Follow-up: moving checked-in travellers; grouping and filtering proposals on the operations page; per-airport minimum connection times.
