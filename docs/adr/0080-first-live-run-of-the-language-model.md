# ADR-0080: The first live run of the language model

**Status:** Accepted
**Date:** 2026-11-06

## Context

The disruption agent (ADR-0022) had only ever run against scripted models and the rule-based first-fit planner. The roadmap said so: "the live model has not been run yet". On 2026-11-06 `python -m evals --live` was run for the first time against Groq. It found three defects and one result that changes the plan.

## What the run found

1. **The default model no longer exists.** `llama-3.3-70b-versatile` answered 404 `model_not_found`. The account lists `openai/gpt-oss-120b`, `openai/gpt-oss-20b` and `qwen/qwen3.8-27b` as chat models. The default is now `openai/gpt-oss-120b`, named in one place (`DEFAULT_GROQ_MODEL` in `config.py`), still overridden by `GROQ_MODEL`.
2. **The model was shown the flight it was moving people away from.** The search still lists a delayed flight, and the prompt says "prefer the earliest flight that fits", so every proposal was the disrupted flight. The rule `NotTheDisruptedFlight` refused all of them (0% coverage, 2 refused, 0 unsafe), so the safety design held, but the model was set up to fail. Fixed in code, not only in the prompt: `DisruptedFlightHidden` removes that exact flight and date from what `find_alternatives` shows the model (the same number on another day stays), the agent applies it to every policy, and the prompt now says why. The rule stays as the second layer.
3. **One typo crashed a whole run.** In a 40-proposal answer the model wrote `"rationale:"` for one key. Groq checks tool calls against our schema on its side, rejected the whole call with a 400 `tool_use_failed`, and the exception ended the run. Now `rationale` is optional in the schema (the `Proposal` model already defaulted it to empty), and `GroqChatModel` asks again up to 3 times on `tool_use_failed`, then gives no answer, which hands the batch to a person. Every other error (a missing model, a bad key, an outage) still raises.

## The result (model `openai/gpt-oss-120b`, after those fixes)

| Scenario | first-fit | Groq | refused | unsafe |
|---|---|---|---|---|
| two bookings, plenty of room | 100% | 100% | 0 | 0 |
| a full day (45 bookings, 46 free seats) | 56% | **11%** | 0 | 0 |
| nothing flies | 0% | 0% | 0 | 0 |
| a fully booked A380 (500 bookings) | 96% | **not measured** | | |

The A380 row was **not measured: Groq's free tier stopped it** with a 429 on tokens per day (200,000 for this model; 196,024 were already used by the earlier runs the same day). A 500-booking flight is about 13 batches of model calls, each with a growing conversation, so one full run probably costs more than a day's free allowance. It needs a paid tier, a lighter model with its own allowance (limits are per model), or a day with the allowance unspent. Run it alone with `python -m evals --live --only "a fully booked A380"`. The code stopped loudly on the 429 rather than reporting a number.

`unsafe` was 0 in every row, and `refused` was 0 once the defects above were fixed. The model is **safe but wasteful** on packing: it hands most bookings to a person ("no seats available") when the seats exist.

## Decision

- Record this as the live baseline, with the model and the date, and keep the first-fit planner as the default (it already is: `SKYHUB_AGENT_PLANNER` defaults to `first-fit`).
- Do **not** build a hybrid planner yet. The premise was that code packs and the model handles the rest. But on this evidence the packing is the whole problem: nearly every booking the model gave up on, first-fit could place. A second stage for "what code cannot place" would mostly be given nothing, and the model's value in these scenarios is unproven.
- `--only NAME` lets one scenario be run alone; `--verbose` prints why each suggestion was refused.

## Consequences

- Positive: the live model has been run, and the three things it broke are fixed with tests (4 new unit tests on the adapter and schema, 2 on the agent, 2 on the runner).
- Positive: no change to what a person sees, and nothing unsafe was ever let through.
- Negative: **one model, one run, one day.** Results from a hosted model drift, and rate limits make a full run slow. The table is a baseline, not a guarantee.
- Negative: the 500-booking row is missing (the free tier's daily token cap), so the claim "the model copes with batching at scale" is not yet shown live.
- Open question for the next slice: **by bookings or by passengers?** "Coverage" counts bookings, so putting small parties first looks better than first-in-line. By arithmetic, about 32 of the 45 bookings in "a full day" could fit if small parties went first, against 25 for first-fit (not checked against the time windows). Whether that is the right thing to optimise (it strands large families) is a decision for the author, not a code detail.

## Verification

All 106 unit and eval tests pass offline. The live numbers above were run by the author on their own machine; they were not reproduced here, because the key is not available to the tools.
