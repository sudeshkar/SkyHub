# ADR-0090: A traveller assistant, and the help centre it answers from

**Status:** Accepted
**Date:** 2026-11-23

## Context

SkyHub has one AI agent, and it is for staff: the disruption agent proposes where affected bookings could go and a person approves (ADR-0022). Travellers have nothing to ask. A question such as "can I still get my money back?" or "why can I not check in yet?" has an exact answer in the code (ADR-0013, ADR-0016), but nowhere a traveller can read it: there is no help centre, no FAQ, and the website only says a rule when it refuses something.

The author wants a traveller assistant: a chat on the website that answers questions about SkyHub and, later, about the traveller's own booking. The plan the author chose is **retrieval over SkyHub's own documents** (RAG), offered to the model as a tool through an **MCP server**, with the agents and tasks set up in **CrewAI**, and the whole assistant served as an **API** the website calls.

This ADR records the shape of the assistant and decides its first slice: what the assistant answers from, and how those answers are kept true.

### The shape of the assistant (the slices that follow this one)

1. **This slice:** the help centre, and a test that ties every rule it states to the code that enforces it.
2. Retrieval over the help centre, offered as a `search_help_centre` tool, with retrieval evals.
3. A **traveller** MCP server with the help-centre and flight-search tools. It is a separate server from the disruption agent's, which holds a staff login; the two trust levels are never mixed in one process.
4. The CrewAI crew (a policy agent that answers from the help centre and names the article, and a trip agent that uses the live tools) behind `POST /api/v1/assistant/messages`, with the contract in `api-specs/` first.
5. Booking tools that act **only with the traveller's own manage token** (ADR-0011), obtained with the booking reference and a last name like the Manage page. No staff token, so the assistant cannot read anyone else's booking.
6. The chat on the website, called from the Next.js server like every other service, and rate limited at the edge (ADR-0009).

Two rules hold for every one of them, as they do for the disruption agent: **the model never changes a booking** (cancelling or changing a seat is done by the traveller on the Manage page, which the assistant links to), and **an answer about a rule comes from an article or is "I do not know"**.

The author chose CrewAI. The disruption agent deliberately uses no framework (a hand-written loop over the Groq client, few dependencies); the assistant is a separate service, so the choice there does not touch the disruption agent. The model is Groq's free tier, as for the disruption agent (ADR-0080): no paid key is needed.

## Options considered

What should the assistant answer from?

1. **The documents SkyHub already has: the ADRs, design documents and READMEs.** Rejected. They are written for engineers: they describe tables, topics and keys, they record options that were rejected and rules that were later replaced (ADR-0014's shared key, superseded by ADR-0017), and an answer built from them would be wrong for a traveller or would tell a traveller how the system works inside.
2. **A help centre written for travellers, by hand, with nothing checking it.** Rejected on its own: it is right on the day it is written and wrong the day a rule changes. A help centre that says "24 hours" after the code says 12 is worse than none, because the assistant would repeat it with confidence.
3. **A help centre generated from the code.** Rejected: rules live in Java constants, Spring defaults and YAML in three services, and the sentences a traveller needs ("you are checked out and need to check in again") are not in any of them.
4. **A help centre written for travellers, where every number it states is registered with the line of code that enforces it, and a test checks both sides.** Chosen.

## Decision

- **The help centre is Markdown, one article per topic,** in `services/traveller-assistant/help-centre/`. Each article starts with a small header: `id` (the file name), `title`, `summary`, and `facts`, the list of registered facts the article states. An unknown header field is refused, so a typo such as `fact:` cannot quietly turn the check off.
- **Nine articles to start:** booking a flight, connecting in Dubai, managing a booking, cancellations and refunds, checking in, boarding, delays and cancellations, accounts, and what SkyHub is (a demonstration, no real payments). They are written from the code and the ADRs, in plain words, and say nothing about how the system works inside.
- **Every rule with a number is a registered fact,** in `help-centre/facts.yml`: its value, the words an article uses to state it (`"{value} hours before"`), the file that enforces it and a pattern that finds the value in that file. Nineteen facts today, from the booking and flight-search services: the check-in and boarding windows, the seat hold, free cancellation, passengers and flights per booking, the manage-booking session, connection and transfer times, the longest layover, and the account rules.
- **The test, `test_help_centre_matches_the_code.py`, fails when:**
  - the code no longer says a fact's value (the pattern must match exactly once, so a file that moved or a second copy is also a failure);
  - an article declares a fact and does not state it in the registered words, or declares a fact that does not exist;
  - **an article contains any number that is not part of a fact it declares.** This is the check that matters most: a writer cannot add "you can cancel up to 48 hours before" without registering where the code says it;
  - a fact is registered and no article states it.
- **The loader is the code the next slice will index.** `traveller_assistant.help_centre.load_articles` reads and checks the articles; retrieval in slice 2 indexes what it returns, so the assistant reads exactly what the test checked.
- **A new Python package, `services/traveller-assistant`,** with its own CI job. It needs nothing but PyYAML today; CrewAI arrives with the crew (slice 4), not before it is used.

## Consequences

- Positive: changing a rule in the code (say, check-in opening 48 hours before) fails a test that names the fact and the file, so the help centre, and through it the assistant, cannot fall behind silently.
- Positive: the articles are useful on their own; the website can show them as help pages with no further writing.
- Positive: no number reaches the assistant from an unchecked sentence.
- Negative: **the test reads the code defaults, not a running deployment.** A setting overridden in the environment (today only the test stack does this, `docker-compose.test.yml`, to open check-in 100 days early) is not seen. If production ever overrides a fact, the help centre must be told by another route.
- Negative: **the check is about numbers, not meaning.** A sentence without a number can still be wrong ("check-in is for the whole party at once" has not been true since ADR-0058). Those are kept right by review, and the evals of slice 2 will ask questions whose answers are known.
- Negative: the patterns depend on how the code is written. Renaming `MAX_PASSENGERS` or moving a default fails the test even though nothing changed for travellers. That is accepted: the failure names the fact and the file, and fixing the pattern takes a minute.
- Negative: a fact can be stated in only one form of words. "60 minutes" cannot also be written "an hour" in another article. Accepted for the sake of a simple check.
- No metric, alert or runbook entry: nothing runs yet. They arrive with the API (slice 4).
- Follow-up work: slices 2 to 6 above; showing the articles on the website as help pages.
