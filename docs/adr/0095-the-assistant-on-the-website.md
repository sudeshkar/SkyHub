# ADR-0095: The assistant on the website

**Status:** Accepted
**Date:** 2026-11-23

## Context

The traveller assistant answers over an internal API (ADR-0093) and can read the booking the website has open, if the website's server sends it (ADR-0094). Travellers cannot reach it yet: this is ADR-0090's last slice, the chat on the website.

Constraints from what is already built:

- **Only the website's server may call the assistant** (a shared token, ADR-0093), as with every SkyHub service: the browser never talks to one (`DEVELOPMENT.md`).
- **The manage token lives in an httpOnly cookie scoped to `/manage`** (ADR-0011). ADR-0078 found that a Server Action is posted to the page it is called from, so a page outside `/manage` never receives that cookie; the booking-page picker of saved names had to be served from under `/account` for the same reason.
- **The free tier answers two or three messages a minute** (ADR-0093), for everyone together.
- The bundled Next.js 16 guide: a Server Action is reachable by a direct POST, not only from the page, so it must check its caller and its input itself; Next.js checks the request's `Origin` against its `Host` (CSRF) and caps the body at 1 MB; actions from one browser run one at a time.

## Options considered

How the browser reaches the assistant:

1. **A route handler (`/api/assistant`) the browser calls with `fetch`.** It would need its own CSRF protection, and to see the manage cookie it would have to live under `/manage`, so the chat would answer about bookings only on that page's subtree, or the cookie would have to be widened (undoing ADR-0011).
2. **A Server Action.** Chosen. Next.js gives it the origin check; called from the Manage page it is posted to `/manage` and so receives the cookie; called from any other page it does not, and the assistant reads no booking. That is ADR-0094's rule ("the booking open on the Manage page") with no cookie widened.

Where the conversation lives:

1. **On the server** (Redis, by a chat id). A copy of what travellers asked, to protect and delete, for no gain: the assistant keeps nothing (ADR-0093).
2. **In the page, in memory.** Chosen. Reloading starts afresh; nothing is written to storage.

## Decision

- **One chat on every traveller page** (`AssistantChat`, a client component in the root layout): a button, "Ask SkyHub", that opens a panel with the conversation, a box for the message and the sources of each answer. Not on the operations pages (`/ops`): staff have their own tools.
- **Shown only when the assistant is configured** (`SKYHUB_ASSISTANT_API_TOKEN` set on the server); otherwise the layout renders nothing, so a site without it has no dead button.
- **`askAssistant(message, history)`, a Server Action** (`app/assistant/actions.ts`):
  - **Rate limited per client**, a new policy `assistant`: 6 messages a minute, the free tier's pace with room for one traveller's conversation. It fails open like every limit (ADR-0009); the assistant still turns away a third answer at once (ADR-0093).
  - **Checks its input** to the contract's limits (a message of 1 to 1,000 characters, at most 6 earlier turns of 1,000 characters, roles `traveller` or `assistant`) before anything is sent: an action is reachable by a direct POST.
  - **Reads the manage session** (`readSession`); there is one only when called from `/manage`. It sends the reference and the token to the assistant as `X-Booking-Reference` and `X-Booking-Token` (ADR-0094), never in the body.
  - **Returns only what the traveller sees**: the reply, the sources' titles, and whether a booking was read. Never the token or the reference.
- **The client** (`lib/api/assistant.ts`), like the disruption agent's (ADR-0024): server-side only, the shared token from the environment, `cache: "no-store"`, a 60-second timeout. Outcomes: an answer; `busy` or `unavailable` with `Retry-After` (a 503: the assistant is busy or the model is down); `rejected` (a 400, the detail shown); `unavailable` for anything else, a 401 included (a wrong token is a setup problem the traveller cannot fix).
- **The conversation is kept in the panel's state**, trimmed to the last 6 turns, each cut to 1,000 characters, which is what the contract allows. Nothing goes to `localStorage` or a cookie.
- **What the panel says**: replies as plain text (never HTML, so nothing a model writes can run in the page); under an answer from the help centre, "From the help centre:" and the articles' titles; a line that the assistant cannot change bookings and that SkyHub is a demonstration; a waiting state while the answer comes; and the failures in plain words ("busy, try again in a moment", "too many messages, try again in a minute").
- **Types from the contract**: `api-specs/traveller-assistant-api.yaml` joins `npm run api:types` (`src/lib/api/assistant-contract.d.ts`), so CI checks the website and the assistant agree.

## Consequences

- Positive: a traveller on the Manage page can ask "is my flight delayed?" and be answered from their booking, and nobody else's, with no reference typed into a chat.
- Positive: nothing new is exposed to the browser: no service address, no token, no reference, and no conversation stored anywhere.
- Negative: **the free tier is shared by every visitor.** At two or three answers a minute for everyone, a busy site turns most messages away with "busy". A paid tier or a smaller model (ADR-0093) is what a real deployment needs.
- Negative: **a reload loses the conversation.** Accepted for privacy and simplicity.
- Negative: **the help-centre titles are not links**: the articles are not website pages yet (ADR-0090's follow-up).
- Negative: not tested in a browser against the live assistant and model in CI (no key there); the component and the action are unit-tested, and the whole chain was checked by hand (see the pull request).
- Follow-up work: the assistant's container image and its place in the compose files and the deployment; help pages for the articles.
