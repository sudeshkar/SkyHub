# ADR-0024: Approving the agent's proposals on the operations page

**Status:** Accepted
**Date:** 2026-10-30

## Context

The disruption agent proposes moves (ADR-0022) and the booking service carries them out (ADR-0023), but approving them meant a terminal. Operations staff already use the operations page (ADR-0018) to record a disruption; it is the natural place to review and approve what the agent proposes. Two questions shape it: how the page gets proposals from a Python agent, and who the audit names when staff approve.

## Options considered

For getting proposals:
1. **The page runs the agent itself** — a Node process cannot host the Python agent, and would need its own model access.
2. **The agent stores proposals in a database the page reads** — a new store, a job to fill it, and a way to know when it is stale.
3. **The agent becomes a small HTTP service the page's server calls on demand** — one authenticated endpoint, no new storage, always fresh.

For who carries out the move:
1. **The agent's service account** — simple, but the audit says "agent" for every move and the person who approved is lost.
2. **The staff member's own login, from the page** — the audit names the person who approved; the agent never needs to hold power to change anything.

## Decision

HTTP service for proposals; the person's own login for moves.

- **`POST /v1/proposals`** on the agent, described in `api-specs/disruption-agent-api.yaml` (contract first: the website's types are generated from it and a test fails if the service drifts from it). It returns, for every active booking on the flight, one proposal: rebook onto a named flight, or "needs a person" with the reason. It holds references and counts only: no names, no emails, no credentials.
- **Only the website's server calls it,** with a shared secret (32+ characters, compared in constant time). The browser never reaches the agent, and the secret never leaves the server.
- **Proposals come from a registered planner.** The default is the rule-based first-fit planner, so the screen works with no model key; a Groq model is one more registered planner, chosen by configuration. The response names the planner, and the page says so. Adding a planner is adding an entry; nothing else changes.
- **Work is bounded.** At most two runs happen at once; a third waits briefly and is then told to try again (503), so a burst of clicks cannot pile up model calls.
- **Approval is a tick and a button.** Every move that can be made is ticked to begin with, and a person unticks what they do not want. Nothing moves until they press the button. Bookings that need a person have no checkbox and show why.
- **Moves go from the page's server straight to the booking service with the staff member's token.** The audit (ADR-0023) therefore names the person who approved. The agent service cannot move anything at all.
- **Large lists go in batches of 20,** one after another, with progress shown, so approving a whole flight never means one long request. A refused move is shown with the booking service's reason and the rest carry on. A refused token ends the run and asks the person to sign in again, keeping what was already moved. A booking service that stops answering ends the run rather than being hammered.

## Consequences

- Positive: operations can handle a disruption entirely on one page: record it, review proposals, approve, see the result and each booking's new seats.
- Positive: the audit trail names the human; the agent cannot change a booking even if compromised.
- Positive: the page works today with the rule-based planner, and gains the model the day a key is configured, with the same screen.
- Negative: one more service to run (and to secure), and a shared secret to manage.
- Negative: proposals are computed per request, so asking twice repeats the work (cheap for the rule-based planner, a cost for a model). Seats and bookings change constantly, so caching them would show stale answers.
- Negative: approvals are applied one booking at a time, in order, so approving 500 bookings takes 25 sequential calls. Fine for one flight; a bulk endpoint in the booking service is the next step if it matters.
- Negative: the page shows proposals as a flat list. Grouping bookings that share a destination flight, a filter, and a search box are not built.
- Follow-up: a bulk rebooking endpoint; grouping and filtering for large flights; showing each booking's passengers' names to staff (only on the booking page, never through the agent).
