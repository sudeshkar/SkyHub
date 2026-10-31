# Roadmap and pending work

SkyHub is built as **thin vertical slices**: each slice is one small, complete piece of behaviour that goes all the way down (contract, tests, backend, website, monitoring, docs), is merged on its own, and leaves `main` working. We do not build a layer across the whole system first. A slice is done when its tests pass and its decision is written down in an ADR.

How we work (standards, security, testing, verification, machine notes) is written in [`DEVELOPMENT.md`](DEVELOPMENT.md).

How a slice goes, every time:

1. Branch from an up-to-date `main`. Pull requests go to `main` (not stacked on other PRs: a stacked PR merges into its parent branch and never reaches `main`).
2. Write the decision first (ADR) and the API contract (`api-specs/`).
3. Tests first, then the code. Backend integration tests run on real PostgreSQL.
4. Website changes with their tests; typecheck, lint and tests green.
5. Metrics, an alert if the failure needs a person, and a runbook entry.
6. Commits are the author's own: no co-author lines.

## Done

| # | Slice | Decision |
|---|---|---|
| 1 | Flight search across DXB and DWC, with airport-changing connections | ADR-0001, 0006 |
| 2 | Seat maps, multi-flight bookings, optimistic locking | ADR-0002 |
| 3 | Security: Spring Security, rate limiting, encryption at rest | ADR-0009, 0010 |
| 4 | Performance: in-memory timetable, seat-map cache, load tests | ADR-0006 |
| 5 | Observability: metrics, JSON logs, tracing, Grafana, SLO alerts, runbooks | `docs/design/04-observability.md` |
| 6 | Manage a booking: change seats, cancel (short-lived token) | ADR-0011 |
| 7 | Seat holds that expire | ADR-0012 |
| 8 | Online check-in with signed boarding passes | ADR-0013 |
| 9 | Flight delays and cancellations | ADR-0014 |
| 10 | Payments with a mock provider (charge, book, refund on failure) | ADR-0015 |
| 11 | Refund on cancellation by simple fare rules | ADR-0016 |
| 12 | Staff login and roles (replaces the shared operations key) | ADR-0017 |
| 13 | Operations screen for recording delays and cancellations | ADR-0018 |
| 14 | Booking-confirmed email: transactional outbox, Kafka, notification service | ADR-0019 |
| 15 | Cancellation (with refund) and flight delay / cancellation emails | ADR-0020 |
| 16 | Real SMTP adapter, Mailpit as the local mail server | ADR-0021 |
| 17 | Disruption agent (Python, MCP, Groq): proposes re-accommodation, guardrails verify it, evals judge it | ADR-0022 |
| 18 | Staff rebooking after a disruption: audited, all-or-nothing, rules re-checked, party kept together; the agent applies only what a person approves | ADR-0023 |
| 19 | Approval screen on the operations page: proposals from the agent over HTTP, approved with the staff member's own login | ADR-0024 |
| 20 | Cancelled flights are not sold; search follows disruptions through a compacted Kafka topic | ADR-0025 |
| 21 | Withdraw a disruption: staff take back a mistaken or resolved delay or cancellation; refused once bookings were moved | ADR-0026 |
| 22 | Connections follow delays: search judges layovers on expected times | ADR-0027 |
| 23 | "Disruption withdrawn" email to every traveller still holding the flight | ADR-0028 |
| 24 | Check-in-open reminder emails, sent by a sweep that scales with flights, not bookings | ADR-0029 |
| 25 | Refunds that failed are retried automatically: charges for failed bookings and refunds owed after cancellation | ADR-0030 |
| 26 | Refund-completed email when a delayed refund is finally paid | ADR-0031 |
| 27 | A tool to list and replay parked (dead-lettered) notification messages | ADR-0032 |
| 28 | Traveller accounts: sign up and reset a password with an emailed link, sign in (no bookings attached yet) | ADR-0033 |
| 29 | My bookings: a signed-in traveller sees the bookings made with their address (keyed index, backfilled for old rows) and opens one without its reference | ADR-0034 |
| 30 | Bulk rebooking: up to 50 moves in one request, each all or nothing, each result reported; the operations page makes one call per batch | ADR-0035 |
| 31 | A rebooking keeps the traveller's connections: the booking service now refuses a replacement that lands too late for the next flight or leaves too soon after the previous one, on expected times | ADR-0036 |
| 32 | Skip a parked notification that can never succeed: listed by partition and offset with a reason, left out of the replay | ADR-0037 |
| 33 | Paging "Your bookings" past the newest 50: a keyset cursor, so every page costs the same | ADR-0038 |
| 34 | Change the account's email address: current password, then a link to the new address, then the change; bookings follow the address | ADR-0039 |
| 35 | Telling the old address when an account moves: a notice with the new address masked, no link, no secret | ADR-0040 |
| 36 | Ending every open session when an account's credentials change: a per-account session epoch carried in the token, checked on every account request | ADR-0041 |
| 37 | Filtering "Your bookings" into upcoming and past, in the query, with the same order and cursor | ADR-0042 |
| 38 | Grouping and finding proposals on the operations page: by destination flight, select all or none per group, find a booking by reference | ADR-0043 |
| 39 | Upcoming bookings listed soonest flight first, by each booking's next flight date, with its own kind of cursor | ADR-0044 |
| 40 | One login lock and one password gate shared by staff and traveller accounts (a refactor: same behaviour, same tables, one copy of the rule) | ADR-0045 |
| 41 | One-time link tokens sealed in the events that carry them (AES-GCM, bound to the event type and address), so the topic no longer holds a usable secret | ADR-0046 |
| 42 | "Sign out everywhere": one action that ends every session of the account, without changing the password | ADR-0047 |
| 43 | One password rule for staff and travellers (12 characters at least, 72 bytes at most), fixing a 500 for passwords BCrypt cannot store | ADR-0048 |
| 44 | A repeatable live check of the account journey through the real chain (booking, Kafka, notification, Mailpit), and the fix for the one defect its first run found | ADR-0049 |
| 45 | The live check extended to the booking journey: search, seats, hold, payment, confirmation, an account, a cancelled flight, a rebooking that keeps the connection, a cancellation and refund (43 checks, no product defect found) | ADR-0049 |
| 46 | Every email also goes out as HTML, derived from its plain text in one place, escaped, with no remote content | ADR-0050 |
| 47 | Past bookings listed by the last flight, most recently flown first, with their own cursor kind | ADR-0051 |
| 48 | Replay, skip and un-skip must say who is running them; recorded with each skip and in the logs (self-declared: the port has no login) | ADR-0052 |

## Next slices (pick one at a time; each is independent)

Ordered by how much they add to the story, not a commitment.

| Slice | What it adds | Notes |
|---|---|---|
| **Fuller rebooking** | Windowing very large proposal lists, and filtering by moved or refused; moving checked-in travellers; cohort grouping so a 500-booking flight needs fewer model calls; multi-leg re-routing; a recorded live eval baseline (needs a Groq key; `python -m evals --live`) | Approval and rebooking exist (ADR-0022, 0023, 0024). Run `python -m evals --live` with a Groq key first: the live model has not been run yet. |
| **More notifications** | a boarding-pass attachment; SMS; non-blocking retry topics | The outbox, Kafka, the notification service and an SMTP adapter exist (ADR-0019, 0020, 0021); each new message is one event type plus one email. |
| **Real payment provider** | Adapter for a real provider, webhooks, 3-D Secure | Option 3 in ADR-0015. |
| **Account follow-ups** | A way for the owner to recover an account that was moved without them; saved passengers | Accounts and My bookings exist (ADR-0033, 0034). Changing an address must prove the new one with a link, as signing up does. |
| **Check-in extras** | Per-passenger check-in, check-out (to allow seat changes), QR/PDF417 boarding pass image | |
| **Gateway and deployment** | Kong gateway, containers for every service, Kubernetes + Helm, SonarQube quality gate | Was slice 8 in the first plan. |

## Smaller loose ends

- **Browser coverage:** `npm run e2e` (Playwright) drives search, the seat hold countdown, payment, finding a booking, cancelling and the refund panel in a real browser. Still not clicked through: check-in and boarding passes, disruption notices, and the refund-pending retry. It runs by hand against a local stack, not in CI.
- **Monitoring gaps:** no Next.js tracing yet. (JDBC spans: each SQL statement is a span, from `datasource-micrometer`.) (The disruptions panel exists; no alert on purpose, since recording a disruption is a deliberate operations action.)
- **Hold abuse:** holds are limited per client at the website (6 a minute); a per-client limit at the gateway is the next step (ADR-0012).
- **Manage-token revocation:** a token stays valid until it expires (15 minutes); there is no way to revoke one (ADR-0011).
- **Homepage hero video:** needs a paid plan on the video-generation service; the start frame is already prepared.
- **Dev setup:** the Java extension's auto-build is off in this machine's local `.vscode/settings.json` (`java.autobuild.enabled: false`); `.vscode/` is not committed, so set it again on a new machine. Auto-build can overwrite `target/classes` and make a slice test fail for no code reason.
- **Knowledge graph:** `graphify` is installed; adding its hook and a note in `CLAUDE.md` was offered, not done.
