# How we build SkyHub

This is the working agreement for the project: how a change is made, what every change must meet, and where to pick up. The list of finished and pending slices is [`ROADMAP.md`](ROADMAP.md); the reasons behind each design are the ADRs in [`adr/`](adr/README.md).

## 1. Thin vertical slices

A slice is one small, complete piece of behaviour that goes all the way down and is merged on its own, leaving `main` working. We never build one layer across the whole system first.

Every slice has the same shape:

1. **Decision first.** An ADR (context, options considered, decision, consequences with honest negatives) and, where there is an interface, the contract (`api-specs/*.yaml`, or a shared event example in `api-specs/events/`).
2. **Tests first.** Write the failing test, then the code (section 2).
3. **Backend**, then **website** (with its tests).
4. **Operability.** A metric; an alert if the failure needs a person; a runbook entry (`docs/runbooks/README.md`).
5. **Docs.** ADR index, `ROADMAP.md` (move the row to Done, add what the slice uncovered), README if setup changed.
6. **Verify, then say exactly what was and was not verified** (section 6).
7. **Pull request** to `main`.

Ask of every slice: *what will the next feature make me edit?* and *what happens at 100x the data?*

## 2. Test-driven development

- Red, green, refactor. Tests describe behaviour in plain language (`should_refuse_a_cancelled_flight_and_say_which`).
- Layers of test, cheapest that can prove the point:
  - **Unit** for domain rules and services, with small fakes or mocks for ports.
  - **Controller / security slice** (`@WebMvcTest` with the real security rules): who may call what, and the exact status codes.
  - **Integration on real PostgreSQL** (Testcontainers) through the real filter chain. Never an in-memory substitute for the database.
  - **Embedded Kafka** for messaging (the Testcontainers Kafka module does not work on this machine).
  - **Architecture tests** (ArchUnit): the domain has no Spring; the application layer does not know the token format or Spring Security.
  - **Frontend:** Vitest and Testing Library; `tsc`, `eslint`, and `next build` (the build catches errors the unit tests cannot, such as Cache Components rules).
- A **shared event example** (`api-specs/events/*.v1.example.json`) is read by the tests of both the producer and the consumer, so the two cannot drift apart.
- Test the failure paths and the worst case, not just the happy path: concurrency (two racing requests), repeats (idempotency), a dependency that is down, a very large input.

## 3. Design: patterns, SOLID, worst-case efficiency

- **Open/closed above all.** Extending the system should mean *adding* a class or registering something, not editing old code. Examples already in the code: guardrail `Rule` classes, `RebookingRule`s, `RefundSource`s, the planner registry, `SeatAllocator` strategies, ports with one adapter each.
- **Single responsibility.** Small single-purpose classes. A timer is its own class apart from the work it triggers, so tests can run the work on demand.
- **Dependency inversion.** The application layer owns the interface (a *port*); an adapter in `integration/`, `persistence/` or `security/` implements it. Use cases never know whether events travel by outbox, Kafka or anything else.
- **Dependencies injected;** no static state, no `new` of collaborators inside use cases.
- **Don't add a pattern for show.** Each one must remove an edit that the next feature would otherwise need. If it doesn't, leave it out.
- **Worst-case efficiency.** Choose data structures and algorithms for the big case (a full A380, thousands of bookings, a slow dependency): hash lookups instead of repeated scans, partial and covering indexes, bounded batches and loops, no N+1, work done per flight rather than per booking. State the complexity where it is not obvious, and what the limit is.
- Domain objects keep their rules (`TravellerAccount.mayBeSentLinkAt`, `Disruption.sameAs`); services coordinate; controllers translate. DTOs at the boundary, generated from the contract.

## 4. Security (every slice, not a later phase)

- **Deny by default.** Only endpoints in the contract are reachable; each is matched explicitly in `SecurityConfig`.
- **Tokens carry a scope and cannot stand in for each other:** `manage` (one booking), `staff` (with a role), `account` (a traveller). Tested both ways.
- **Never say more than needed.** One answer for every failure in a login (unknown user, wrong password, locked, no password yet); one 400 for a wrong, used or expired link; account requests always answer 202. Unknown identities cost the same time as real ones (a decoy hash).
- **Secrets are stored as hashes** (passwords with BCrypt; one-time tokens with SHA-256). A copy of a table must not be a way in.
- **Personal data is encrypted at rest** (AES-GCM); to look something up by an encrypted value, store a keyed hash (`PiiCipher.blindIndex`). It never appears in logs, `toString()`, metrics, or error text; masked forms only.
- **Lock-outs and rate limits** at two layers: the website (per client, Redis sliding window) and the service (per account).
- **Idempotency** for anything that moves money or sends something: idempotency keys, unique-key claims before side effects, `ON CONFLICT DO NOTHING`, and consumers that record each event id once.
- **Transactional outbox** for anything another service must hear about, written in the same transaction as the change.
- **Browser side:** tokens only in `httpOnly`, `SameSite=Strict` cookies scoped to their path; pages that hold secrets in the address are `noindex` with no referrer; the browser never talks to the Java services directly.
- **Humans decide the risky things.** The disruption agent proposes, code verifies, a person approves; moving is deliberately not a tool the model can call.
- Say plainly what an ADR's security design does **not** cover (for example, the one-time account token passes through Kafka).

## 5. Git and pull requests

- Branch from an up-to-date `main`; one slice per branch; the PR goes to `main`, **never stacked** on another PR.
- **Commits and PR bodies are the author's own: no `Co-Authored-By` and no "Generated with" lines.**
- The author merges. After "merged", confirm with `gh pr view <n> --json state`, then `git checkout main && git pull`.
- A PR body says what, how, the limits, and what was verified *and what was not*.

## 6. Verification before saying "done"

- Run the whole build of every service touched: `JAVA_HOME="C:\Program Files\Java\jdk-26.0.2" ./mvnw -B -q -f services/<service>/pom.xml verify`, then read the Surefire reports (the console is full of expected log noise).
- Frontend: `npx vitest run`, `npx tsc --noEmit`, `npx eslint src`, and `npx next build` when pages or server actions changed. After changing the booking contract, run `npm run api:types` (CI checks the generated types match).
- Alert rules: `promtool check rules` (run it in Docker, see below).
- Where it matters, **prove the real chain live** (booking, Kafka, notification, Mailpit) and report it. Where something was not clicked through in a browser, or not run against a real provider, **say so**.
- Report outcomes faithfully: a failing test is reported with its output, never hidden.

## 7. Machine notes

- Windows 11, Git Bash and PowerShell, JDK 26.0.2 (pass as `JAVA_HOME` to `./mvnw`), Docker, Node 24.
- **Git Bash heredocs break on apostrophes.** Write scripts with an editor tool, then run them. Python edit scripts should read and write UTF-8 explicitly and keep each file's line endings.
- Port **9092 is Kafka**; do not use it for a service's management port.
- A running service's keys (`SKYHUB_PII_KEY` and the others) are not recoverable from the process. To test new code without losing readable data, start a second instance on another port with a throwaway database.
- After adding environment variables, restart the Next dev server. An old dev server hides errors such as `"use server"` export mistakes.
- `GROQ_API_KEY` is not set here, so the Groq planner is only tested with scripted models.
- Promtool: `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/observability/prometheus:/p" --entrypoint promtool prom/prometheus:latest check rules /p/alerts.yml`.
- Local staff accounts (dev only): see the README. Emails are caught by Mailpit at <http://localhost:8025>.

## 8. Where to pick up, and the plan

The full table is [`ROADMAP.md`](ROADMAP.md). The order we plan to work in (each is one or more thin slices, picked one at a time):

1. **Fuller rebooking** (the bulk endpoint, the connection rule and grouping proposals on the operations page are done, ADR-0035, 0036, 0043). Windowing very large proposal lists and filtering by moved or refused; moving checked-in travellers; cohort grouping so a 500-booking flight needs fewer model calls; multi-leg re-routing; a recorded live eval baseline.
2. **More notifications.** HTML email and a boarding-pass attachment; SMS; non-blocking retry topics. (Skipping one parked message that can never succeed is done, ADR-0037.)
3. **Real payment provider.** An adapter for a real provider, webhooks, 3-D Secure (option 3 in ADR-0015). Also write an intent before charging, so a crash between charging and recording cannot lose a charge (ADR-0030).
4. **Check-in extras.** Per-passenger check-in, check-out (to allow seat changes), a QR/PDF417 boarding-pass image.
5. **Gateway and deployment.** Kong gateway, containers for every service, Kubernetes with Helm, a SonarQube quality gate, a per-client hold limit at the gateway.

**Account follow-ups (small slices, any time):** a "sign out everywhere" action, a way for the owner to recover an account that was moved without them (the old address is now told, ADR-0040, and sessions end on a change, ADR-0041), saved passengers, sort upcoming bookings by flight date. (Paging past the newest 50 is done, ADR-0038, and so is filtering into upcoming and past, ADR-0042.)

**Loose ends to fold in when a slice touches them:** browser tests (Playwright) for check-in and boarding passes, disruption notices, the refund retry, the withdraw form and the account pages; Next.js tracing; manage-token and account-token revocation; pass only a reference (not the token) through Kafka for account links; share the lock-out logic between staff and traveller accounts; homepage hero video (needs a paid plan); the graphify hook.

**Needs the author, not code:**
- Set `GROQ_API_KEY`, then run `python -m evals --live` and `python -m disruption_agent SK001 <date>`: the live Groq model has never been run.
- Restart the booking service with its usual keys to get the account and My bookings endpoints (the first start indexes older bookings, see ADR-0034); set `SKYHUB_SITE_URL` anywhere that is not local.
- On a new machine, turn the Java extension's auto-build off in `.vscode/settings.json` (it can overwrite `target/classes`); `.vscode/` is not committed.
