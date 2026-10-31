# ADR-0061: Browser coverage on the live stack

**Status:** Accepted
**Date:** 2026-11-10

## Context

Several recent slices changed what a person sees and clicks: per-passenger check-in and check-out (ADR-0058), closing a booking so a copied token stops working (ADR-0057), and the "take your account back" page (ADR-0054). Each pull request said the page was unit-tested and typechecked but **not clicked through in a browser**. The Playwright suite (`npm run e2e`) existed but had never been run against the stack that can reach check-in (ADR-0059), and it had no spec for any of these.

## Decision

`frontend/e2e/check-in.spec.ts` drives the real website against the live stack, with the API used only for set-up (a booking of two passengers, an account, an address move) and the browser for everything a person would click:

- **Check-in one passenger at a time:** one passenger checks in alone, only their pass shows, "1 of 2 checked in", only their seat is frozen while the other passenger changes seat through the real seat picker; the flight's button checks in whoever is left; one passenger checks out and their seat can change again; everyone checks out and in again.
- **Closing the booking:** the cookie's token is read before the click; afterwards the same token is refused by the booking service (401), not only the cookie dropped.
- **The undo page** (needs `SKYHUB_MAILPIT_URL`, for the emailed links): opening the link from the notice changes nothing, pressing the button takes the account back, the person who moved it is shut out, a reset link reaches the owner, and the same link then says it no longer works.

How to run it: `python tools/live-check/stack.py up` (it opens the check-in window wide enough, ADR-0059), `docker compose up -d redis` (the website's rate limit), the website, then `npm run e2e`. **Clear the rate-limit counters between runs** (`redis-cli --scan --pattern 'rl:*' | xargs redis-cli del`): the lookup limit is 10 per 15 minutes per client, and a few runs use it up, which shows as "Too many attempts" in the page. That is the limiter working, not a failure of the page.

The existing `ops-disruption` spec had gone stale: the ops page gained a second form that also asks for a flight, so `getByLabel("Flight number")` matched two inputs. It is now scoped to the form that records.

## Consequences

- Positive: the pages and buttons of the last four slices are exercised in a real browser against the real services, not only in unit tests.
- Positive: closing a booking is proved end to end: the website, the booking service and the refusal of a copied token.
- Negative: **still run by hand, not in CI.** It needs the whole stack, Redis, the website and a browser, and about a minute once they are up.
- Negative: the rate limiter makes repeated runs fail until its counters are cleared; the specs do not clear them themselves.
- Negative: the browser is Chromium only, desktop size only.
- Not covered: the gate (no screen exists), the ops approval flow (needs the model agent; that spec is skipped without it), and the website on a phone.
