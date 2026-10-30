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

## Next slices (pick one at a time; each is independent)

Ordered by how much they add to the story, not a commitment.

| Slice | What it adds | Notes |
|---|---|---|
| **Disruption agent (Python, Groq)** | An agent that reads a disruption, finds the affected bookings and proposes re-accommodation; MCP server exposing booking tools; an eval suite | The "Python/AI" part of the plan. Uses Groq's free API key (no Claude key). Builds on slice 9. |
| **Notifications with an outbox** | Email/SMS on booking, disruption, check-in opening; transactional outbox + Kafka | Replaces "travellers are not notified". Introduces a broker. |
| **Staff login and roles** | Real operations login instead of the shared `SKYHUB_OPS_KEY`; audit of who recorded a disruption | Spring Security + JWT; roles. |
| **Ops screen for disruptions** | A small page to record a delay or cancellation and see affected bookings | Needs staff login first (or a local-only tool). |
| **Disruptions in search** | Flight search shows delayed/cancelled flights | flight-search would read disruptions. |
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
- **README screenshots** of search, seat map, manage page and Grafana.
- **Homepage hero video:** needs a paid plan on the video-generation service; the start frame is already prepared.
- **Dev setup:** the Java extension's auto-build is off in this machine's local `.vscode/settings.json` (`java.autobuild.enabled: false`); `.vscode/` is not committed, so set it again on a new machine. Auto-build can overwrite `target/classes` and make a slice test fail for no code reason.
- **Knowledge graph:** `graphify` is installed; adding its hook and a note in `CLAUDE.md` was offered, not done.
