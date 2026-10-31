# ADR-0062: The gate screen

**Status:** Accepted
**Date:** 2026-11-11

## Context

ADR-0056 and ADR-0060 gave a gate agent an API: check whether a scanned pass is still good, and board the passenger once. They also gave staff a `GATE` role that can do only that. But the only website screens were for operations staff, so nobody could actually work a gate. Writing the screen also turned up a defect in the previous slice: the website's staff session parser accepted only the roles `OPS` and `ADMIN`, so a gate agent who signed in got a cookie the website treated as **"not signed in"**. The booking service issued the role; the website could not hold it. The unit tests of ADR-0060 did not cover the website side, and the PR said it had not looked at how the screens handle the role.

## Decision

- **A gate panel on `/ops`.** A gate agent sees only the gate (the operations forms would only be refused); operations and administrators see their forms and the gate below. It has one field, "Scanned boarding pass" (a scanner types a pass like a keyboard, so the field is all it needs), and two buttons: **Check pass** and **Board passenger**.
- **Checking never boards.** The button pressed is sent as `intent`; anything but an explicit `board` only checks, so a stray submit cannot board anyone. The panel says so in words.
- **Plain words, and what to do.** A good pass shows the passenger, flight, date, seat and boarding number, and after boarding says "X has boarded". A refused pass says what to do ("Do not board", "Send them to check in", "Ask for the current pass") and, as the API does, **nothing about the passenger**.
- **Nothing is kept.** The pass text goes only to the booking service; the browser gets back only what the gate needs (no pass text, no booking reference), the field clears after every scan, and nothing is logged.
- **Whether this person may is the booking service's decision** from their token, as for every ops action. A refusal clears the cookie and says to sign in again.
- **The session parser accepts `GATE`**, and the page names the role ("gate agent"). This is the fix for the defect above.
- Requests are limited like other manage actions (30 a minute per client). A pass is under 300 characters and the field and the action both say so.

## Consequences

- Positive: a gate agent can sign in, scan and board, in a browser, with a token that can do almost nothing else.
- Positive: the whole check-in and boarding thread now has a person-facing end, proved by a browser test.
- Negative: **the rate limit is per client address, not per gate.** Many scanners behind one address share 30 scans a minute, which is about 9 a minute at a busy gate but is a limit nobody has measured against a real gate.
- Negative: **a gate must be online and has no offline mode;** and there is no sound or colour-only signal, only words.
- Negative: no camera scan; it relies on a scanner that types, or pasting. A phone camera is a later step.
- Negative: boarding still has no time rule and cannot be undone (ADR-0060).
