# ADR-0022: The disruption agent proposes, code verifies, a person decides

**Status:** Accepted
**Date:** 2026-10-30

## Context

When a flight is delayed or cancelled, operations staff must work out who is affected and where each booking could go instead (ADR-0014). Today that is manual. A language model is good at reading a messy situation and writing a sensible suggestion; it is also able to be confidently wrong: to invent a flight, ignore a seat limit, or miss a connection. Moving a traveller onto a flight that does not exist is worse than doing nothing.

The agent also touches personal data (bookings) and credentials (a staff login), and it must keep working when a flight has hundreds of bookings.

## Options considered

1. **A free-running agent that calls the booking API and rebooks people** — fast, and the failure mode is unacceptable: a wrong guess changes a real booking.
2. **A model that only writes advice in prose** — safe, but staff must re-check every claim by hand, so it saves little.
3. **The model proposes in a fixed structure; code checks every proposal against facts it gathered itself; a person approves** — the model supplies judgement, code supplies guarantees.
4. **No model: a rule-based planner** — predictable and cheap. It is also the baseline the model has to beat to justify its place.

## Decision

Option 3, measured against option 4.

- **The agent never changes anything.** It reads, proposes, and prints. Approving and carrying out a proposal is a separate, later step with its own login, audit and screen.
- **The model proposes; the guardrails decide what is believed.** A proposal can only name a flight that a search tool actually returned during the run, which must be on the same route, not the disrupted flight, leave no earlier than the traveller can and land in time for their next flight, and have seats for every passenger, counting everyone already accepted. Anything that fails becomes "needs a person", with the reason recorded. Every booking gets exactly one answer, whatever the model says or forgets.
- **Rules are small classes run by a pipeline** (single responsibility; open/closed). A new safety check is one new class and one line in the list; no existing rule or the pipeline changes. The same holds for tools (a registry), the model (a `ChatModel` port with Groq as one adapter) and how tools are reached (a `ToolBox` port with MCP as one adapter).
- **Tools are exposed through MCP.** The tools are a standard MCP server, so this agent, a different agent or an MCP client such as Claude Desktop can use them. The server runs as its own process and holds the staff credentials; the model only ever sees tool results.
- **No personal data reaches the model.** A new staff-only endpoint (`GET /api/v1/ops/flights/{flight}/departures/{date}/bookings`) returns a booking's reference, passenger count, route and position, and nothing else: no names, no email. Tests check this on both sides. The agent logs in as an `OPS` service account (ADR-0017).
- **Worst case: a full A380.** Work is bounded at each step.
  - The model sees at most 40 bookings at a time; each batch is a separate conversation, so context does not grow with the flight.
  - Flights, route searches and seat counts are remembered (an LRU cache with a time limit and a size limit), so the number of HTTP calls depends on the number of *distinct flights*, not bookings. Five thousand bookings on one flight cost three timetable lookups in the tests.
  - A tool answer is capped at 200 bookings and 8 alternatives. Searches stop as soon as enough alternatives are found, and seats are only counted for flights that already pass the cheap time checks.
  - The guardrails do O(1) dictionary lookups per proposal. Twenty thousand proposals verify in well under a second in the tests.
  - One seat ledger is shared by the capacity rule and by the results shown to the model, so later batches are told which seats earlier batches took. An early version did not, and the evals showed it: safe answers, but the model kept proposing full flights.
- **Judged, not trusted.** The `evals/` folder holds a rule-based first-fit planner (the baseline), an independent oracle that re-derives every rule from raw data and shares no code with the guardrails, and a fuzz test that throws 200 random worlds at a deliberately bad model (invented flights, the disrupted flight, duplicates, forgotten and made-up bookings). The answer a person sees must have zero violations every time. `python -m evals --live` runs the real Groq model on the same scenarios and prints coverage and refused suggestions next to the baseline's.

## Consequences

- Positive: a hallucinating, stubborn or broken model cannot produce an unsafe answer; the worst outcome is "a person decides", which is today's process.
- Positive: the model can be swapped (another provider, a bigger model, no model) by writing one adapter, and the evals say whether the swap is an improvement.
- Positive: the same tools serve other agents and clients without change.
- Negative: **the Groq model has not been run against this agent.** No API key was available while it was built. The whole pipeline is tested with scripted models, a fuzzing model and a rule-based planner, and was run end to end on the real stack with the baseline planner over real MCP; what is unproven is how well a real model uses the tools and the prompt. `python -m evals --live` is how to find out.
- Negative: it proposes only direct flights on the disrupted leg's own route. Re-routing through another hub, moving several legs of one trip together, cabin and fare differences, and keeping travelling parties on one flight are not handled.
- Negative: "earliest that fits" is the only preference. Loyalty, fare, a person's accessibility needs and a delayed flight's real new time are not considered (the timetable time is used).
- Negative: batches are processed one after another so that seat counts stay consistent; a 500-booking flight takes about 13 model conversations. Grouping bookings with identical windows into cohorts would shrink that, and is the next optimisation if it matters.
- Negative: seat counts are read from the seat map, which includes seats held for a few minutes by someone else. A proposal can be refused at approval time; staff then see why.
- Follow-up: an approval screen and a step that rebooks (with audit), cohort grouping, multi-leg re-routing, a live eval baseline recorded in the repository.
