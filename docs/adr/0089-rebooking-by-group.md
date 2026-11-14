# ADR-0089: The model chooses flights per group, and code fills them (families first)

**Status:** Accepted
**Date:** 2026-11-14

## Context

ADR-0080 recorded the first live run of the language model: safe (nothing unsafe, nothing refused once three defects were fixed), but it placed only 11% of the bookings on "a full day" where first-fit placed 56%, and the 500-booking A380 could not be measured, because about 13 long model calls used up the free tier's daily tokens. Asked booking by booking, the model had to share out seats among dozens of bookings in its head, and it mostly gave up.

ADR-0080 left one question to the author: when there are not enough seats, who goes first, by bookings or by passengers?

## Options considered

1. **Keep asking per booking and tune the prompt.** Rejected: the problem is arithmetic over dozens of bookings and shared seats, which a model is bad at and code is exact at, and the calls still grow with the size of the flight.
2. **Hybrid: code places what it can, the model handles the rest.** Rejected in ADR-0080, because almost nothing is left over for the model.
3. **Group the bookings that need the same thing, let the model choose the flights for each group, and let code fill them.** Chosen. Bookings with the same route and the same window (earliest departure, latest arrival) can use exactly the same flights, so a group is one line to the model: how many bookings, how many people, which party sizes, which route and window. The model makes the judgement (which flights, in what order); code does the counting.

For filling, the author chose **families first** over "most bookings" and "most passengers": in a squeeze the largest parties are placed first and a party is never split, so a family of four is not stranded while singles fly. It places fewer bookings than smallest-first; when every seat is used, it moves the same number of people.

## Decision

- **Two strategies, one agent** (`strategies.py`): `PerBooking` (as before, ADR-0022; the rule-based first-fit planner answers this way) and `ByGroup`. The agent runs either unchanged, and both end in the same `GuardrailPipeline`, so every booking still gets exactly one checked proposal.
- **Grouping** (`groups.py`, O(n)): the key is route, earliest departure and latest arrival, so every member of a group fits every flight that fits the group. Groups are numbered G1, G2, ... and fed to the model in bounded batches like bookings were.
- **What the model sees and says:** one line per group (no booking references, names or contacts), the same `find_alternatives` tool, and a new final tool, `submit_plan`: per group, `REBOOK` with the flights to fill in order of preference, or `NEEDS_HUMAN` with a reason.
- **Filling** (`pack`): the group's bookings, largest parties first (then by reference, so it is repeatable), each onto the first chosen flight that passes every guardrail rule and still has room. Room counts every seat this run has planned, across batches and groups. A booking that fits nowhere goes to a person with the reason. A chosen flight the search never returned is not used, and is recorded as refused.
- **The guardrails still check everything afterwards.** Filling is planning, not the safety check.
- **The prompt says plainly that partly filling is expected.** The first live run of the new mode placed nothing: the model reasoned "46 seats for 79 passengers is not enough" and handed whole groups to a person. The prompt now says code moves as many as fit, and `NEEDS_HUMAN` is only for "no flight at all in the window".
- **The language model plans by group** in the web service and the CLI; first-fit stays per booking and stays the default planner.
- **Evals:** a rule-based group planner (`GroupFirstFitPlanner`, every flight its search returned, earliest first) as the yardstick for the new mode; a **people** column beside coverage; `--compare` to also run the model per booking; the eval runner waits out Groq's per-minute limit (8,000 tokens a minute on the free tier) instead of losing the measurement. A second chaos test throws 200 random worlds at a deliberately bad group planner (invented flights, the disrupted flight, flights outside the window, duplicate, unknown and forgotten groups); the independent oracle finds nothing unsafe.

## Results (live, model `openai/gpt-oss-120b`, 2026-11-14)

| Scenario | first-fit | groups (code) | groq per booking | groq by group | unsafe |
|---|---|---|---|---|---|
| two bookings, plenty of room | 100% / 100% | 100% / 100% | 100% / 100% | 100% / 100% | 0 |
| a full day (45 bookings, 46 seats) | 56% / 55% | 40% / 55% | 31% / 31% | **40% / 55%** in 4 of 5 runs, 22% / 31% in 1 | 0 |
| nothing flies | 0% | 0% | not run | 0% | 0 |
| a fully booked A380 (500 bookings) | 96% / 96% | 90% / 96% | not run (token cost) | **90% / 96%** in 3 of 5 runs, 45% / 64% in 2 | 0 |

Each cell is bookings placed / people moved. The A380 now takes one model call and about 20 seconds; before, it could not finish within a day's free allowance. On its good runs the model chooses exactly what the code yardstick chooses, so every free seat is used. On its bad runs it left one flight out of its list. The per-booking figure on "a full day" (31%) is better than ADR-0080's 11% for the same model: hosted models drift.

## Consequences

- Positive: the model now plans as well as code on most runs, in one short call per batch of groups instead of one long call per 40 bookings, and nothing it says can put more people on a flight than it has seats.
- Positive: families are kept together and placed first, as the author chose, and the people moved are the most the seats allow.
- Negative: **fewer bookings are placed than first-fit** when seats are short (40% against 56% on a full day), by design.
- Negative: **the model is not repeatable**, even at temperature 0: 2 of 5 A380 runs left a flight out. The follow-up is to ask the model once more when its plan leaves out a flight its own search returned with free seats, or to show the people left over and let it add flights.
- Negative: the rule-based group planner does as well as the model here, so the model's value is still its judgement in cases these scenarios do not test (for example a preference between hubs, or a late flight versus the next morning).
- Not done: multi-leg re-routing (moving a booking through another hub), which stays on the roadmap.

## Verification

- 125 agent tests pass offline (`python -m pytest`), 19 of them new: grouping, families-first filling, seats counted across groups and batches, a chosen flight that breaks a rule is skipped, a whole flight as one request, an invented flight recorded as refused, groups in bounded batches, no credential or address shown, the web service running a planner by group, every scenario safe by group, and the 200-world chaos test.
- The live numbers above were run on the author's machine with the author's Groq key, five times for the two scenarios that vary.
