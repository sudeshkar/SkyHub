# Roadmap and pending work

SkyHub is built as **thin vertical slices**: each slice is one small, complete piece of behaviour that goes all the way down (contract, tests, backend, website, monitoring, docs), is merged on its own, and leaves `main` working. We do not build a layer across the whole system first. A slice is done when its tests pass and its decision is written down in an ADR.

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

## Next slices (pick one at a time; each is independent)

Ordered by how much they add to the story, not a commitment.

| Slice | What it adds | Notes |
|---|---|---|
| **Fuller rebooking** | A bulk rebooking endpoint; grouping and filtering proposals for big flights; arrival times and a connection rule in the booking service; moving checked-in travellers; cohort grouping so a 500-booking flight needs fewer model calls; multi-leg re-routing; a recorded live eval baseline (needs a Groq key; `python -m evals --live`) | Approval and rebooking exist (ADR-0022, 0023, 0024). Run `python -m evals --live` with a Groq key first: the live model has not been run yet. |
| **More notifications** | A "disruption withdrawn" email (ADR-0026); check-in-opening reminders; a refund-completed email; HTML email and a boarding-pass attachment; SMS; non-blocking retry topics and a dead-letter replay tool | The outbox, Kafka, the notification service and an SMTP adapter exist (ADR-0019, 0020, 0021); each new message is one event type plus one email. |
| **Reconcile charges without a booking** | A job that finds charges with no booking and refunds them | Removes the manual step in the `RefundFailed` runbook. |
| **Real payment provider** | Adapter for a real provider, webhooks, 3-D Secure | Option 3 in ADR-0015. |
| **User accounts** | Sign in, see all my bookings, saved passengers | Today a booking is found by reference + last name only. |
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
