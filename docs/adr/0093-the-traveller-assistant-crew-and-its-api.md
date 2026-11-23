# ADR-0093: The traveller assistant's crew and its API

**Status:** Accepted
**Date:** 2026-11-23

## Context

ADR-0090 planned a CrewAI crew behind `POST /api/v1/assistant/messages`, using the tools of the traveller MCP server (ADR-0092). ADR-0091 left one risk to this slice: a travel question the help centre does not cover ("how many kilos of baggage can I bring?") still retrieves near passages, so whether the assistant says "I do not know" depends on the model.

Facts found while building it:

- **CrewAI's built-in MCP tools start a new MCP client for every tool call**, and over stdio a new client is a new server process. Starting `skyhub-traveller` takes about **3.1 s** (the embedding model and the index); a tool call on a running server about **0.2 s** (measured). Every answer would pay seconds per tool call. `MCPServerAdapter` (`crewai-tools[mcp]`) instead keeps **one** server process for its lifetime.
- **CrewAI has no Groq provider of its own, and its generic `LLM` strips Groq's model name.** `LLM("openai/gpt-oss-120b", custom_openai=True, ...)` sends `gpt-oss-120b`, which Groq does not know. CrewAI's OpenAI provider used directly (`OpenAICompletion`) keeps the name and points at Groq's OpenAI-compatible endpoint.
- **CrewAI sends usage telemetry by default.** It is switched off with `CREWAI_DISABLE_TELEMETRY`, `CREWAI_DISABLE_TRACKING` and `OTEL_SDK_DISABLED`.
- **A spike with the live model** (Groq, `openai/gpt-oss-120b`): "When can I check in online?" was answered correctly from `checking-in-online` in 4.4 s, 2 model calls, about 2,000 tokens; "how many kilos of baggage?" was declined ("I don't have information on the baggage-weight allowance"), 6 calls, about 4,600 tokens. Groq's free tier allows about 8,000 tokens a minute for this model (ADR-0089), so **two or three messages a minute**.

## Options considered

How the crew is organised:

1. **One agent with both tools.** Fewest calls, but every message carries both tools' schemas and instructions, and there is nothing for CrewAI's agents and tasks to do that a single loop would not.
2. **A hierarchical crew (a manager agent delegating).** CrewAI's own pattern for this, but the manager costs extra model calls on every message, which the free tier cannot afford.
3. **A small router call, then a one-agent crew for the kind of question.** Chosen. A policy agent with only `search_help_centre`; a trip agent with `search_flights` (and the help centre, for "when can I check in for it?"). A message that is not about SkyHub is answered without running a crew at all.

How an answer is trusted:

1. **Trust the model's answer.** Rejected: the whole risk of ADR-0091.
2. **The model proposes, code checks** (the rule of ADR-0022). Chosen.

## Decision

- **The router** is one short call of the model, given the last few turns and the message, answering `help`, `flights` or `other`. Anything else it says is read as `help`, the safe side, because the help answer is the one most checked.
- **The crews** (`traveller_assistant.crew`): CrewAI `Agent`, `Task` and `Crew` objects built per message (so two travellers never share state), sequential, at most 4 steps per agent. The task gives the agent today's date (UTC), the conversation so far, the rules (only from the tools, plain words, at most four sentences, never ask for a card number or a password) and the format: the answer, then a last line `SOURCES: <article ids>` or `SOURCES: none`.
- **Code checks every answer** (`traveller_assistant.assistant`), with each tool wrapped for the message so that code knows exactly what the tools returned:
  - an article in `SOURCES` counts only if `search_help_centre` returned it in this run; others are dropped;
  - a help answer with **no** counted source is not shown: the traveller gets the standard "I do not know" reply (`declined` if the model said `SOURCES: none`, `unverified` if it named articles the tool never returned);
  - every flight number in an answer must be one `search_flights` returned in this run, otherwise the standard "I could not check flights" reply (`unverified`);
  - the sources in the API's answer (article id and title) come from the tool results, not from the model's words.
- **The model** is Groq's `openai/gpt-oss-120b` (as the disruption agent, ADR-0080), through `OpenAICompletion` at Groq's OpenAI-compatible endpoint, temperature 0. `GROQ_MODEL` changes it. With no `GROQ_API_KEY` the service does not start.
- **The MCP server is started once**, when the API starts, with `MCPServerAdapter` over stdio (ADR-0092's decision stands: no port), and stopped with it.
- **The API** (`traveller_assistant.web`, Starlette and uvicorn like the disruption agent's), contract in `api-specs/traveller-assistant-api.yaml`:
  - `POST /api/v1/assistant/messages` with `{message, history}`: a message of 1 to 1,000 characters and at most 6 earlier turns of at most 1,000 characters each (the website keeps the conversation; the service keeps nothing). The answer is `{reply, route, outcome, sources}`, never cached.
  - **Only the website may call it**: a shared token of at least 32 characters (`SKYHUB_ASSISTANT_API_TOKEN`), compared in constant time, as for the disruption agent (ADR-0024). It listens on `127.0.0.1:8085` unless told otherwise.
  - **At most 2 answers at once**; a third waits 2 seconds and is then told to try again (`503`). The model being unavailable or rate-limited is also a `503` with `Retry-After`, worded for the traveller.
  - `GET /healthz`, and `GET /metrics` for Prometheus: `skyhub_assistant_messages_total{route,outcome}` and `skyhub_assistant_message_seconds`.
- **Answer evals** (`evals/test_answers.py`, `python -m evals.answers`) run the real crew, the real MCP server and the real model against a local stand-in for flight-search serving fixed itineraries. They run only when asked: `python -m evals.answers` prints each question, and the pytest version runs only with `SKYHUB_LIVE_EVALS=1` and a `GROQ_API_KEY` (in the environment or the repository's git-ignored `.env`, never printed), so neither CI nor an everyday `pytest` spends the free tier's tokens. They hold: every not-covered question is declined; every off-topic message is routed `other`; no answer names a flight that was not returned; and most help and flight questions are answered with the right article or flights.

## Measured, and found while measuring

Live answer evals (`python -m evals.answers`), Groq `openai/gpt-oss-120b`, 2026-11-23:

| Group | First run | After the two fixes below (two runs) |
|---|---|---|
| Help (right article and the fact) | 4 of 6 | 6 of 6, 6 of 6 |
| Not covered (standard "I do not know") | 5 of 5 | 5 of 5, 5 of 5 |
| Off topic (routed `other`) | 3 of 3 | 3 of 3, 3 of 3 |
| Flights (the right flight numbers) | 1 of 2 | 2 of 2, 2 of 2 |
| Answers replaced for naming a flight the search never returned | 0 | 0, 0 |

About 3 to 7 seconds an answer, about 1 for an off-topic message. The real service (`python -m traveller_assistant.web`) was started and asked about a refund: up in 8 seconds, a correct answer from `cancellations-and-refunds` in 2 model calls and 1,347 tokens, 401 without the token, both counted in `/metrics`.

- **The two help misses were the check's fault, not the model's.** Both answers were right ("at least 75 minutes", "10 minutes before"), but the model put `SOURCES:` at the end of the answer's own line, after a narrow no-break space, and the first parser only read it on a line of its own. The parser now takes the last `SOURCES:` marker wherever it is, and reads unusual spaces as spaces; tests hold both forms.
- **The flight miss was an answer without flight numbers**, which neither a traveller nor the check can use. The trip agent's task now asks for every flight by its number.
- **The first draft passed the API's whole environment to the MCP server**, the model's key and the API token included, against ADR-0092's "no credentials". The server is now started with the MCP SDK's safe list of variables and where flight-search is, and a test checks the key and the token are not among them.
- **CrewAI tries a failing model call three times itself**, on top of the OpenAI client's own retries. The service asks the client for one retry (a traveller is waiting); the evals for six.

## Consequences

- Positive: an answer about a rule reaches a traveller only with an article the tool returned behind it, and an answer about flights only with flights that exist. The model can still word a correct passage wrongly; it cannot invent a source or a flight.
- Positive: the MCP server's start-up is paid once per process, not per tool call.
- Positive: an off-topic message costs one short call, not a crew.
- Negative: **two or three messages a minute on Groq's free tier.** Fine for a demonstration; a real deployment needs a paid tier or a smaller model, which `GROQ_MODEL` allows but the evals must then be rerun.
- Negative: **the check is about sources and flight numbers, not every sentence.** A model that cites the right article and then misstates it (says 48 hours where the article says 24) is not caught by code. The answer evals look for the key fact in the answers they check; real conversations are not checked.
- Negative: **the live answer evals do not run in CI** (no key there, and they spend the free tier's tokens). They are run by hand, and their result is recorded here and in the pull request.
- Negative: the router can send a question to the wrong agent; the policy agent then has no flight tool and the trip agent's answers about rules still pass the same source check.
- Negative: the alert and the Prometheus scrape target are not added yet: the service has no container and is not in the compose files. They come with that slice. No runbook entry until there is an alert.
- Follow-up work: booking tools that use only the traveller's own manage token (ADR-0090's slice 5); the chat on the website (slice 6); a container image with the model baked in, and the alert.
