# ADR-0083: The whole stack in containers

**Status:** Accepted
**Date:** 2026-11-06

## Context

ADR-0081 and ADR-0082 made each of the five parts of SkyHub an image. Nothing yet ran them together: starting the system still meant the infrastructure in compose plus four processes from an IDE and a website dev server. This slice adds one command that starts everything, with the order, the addresses and the health checks written down.

## Options considered

1. **A compose profile in the existing file.** Rejected after trying the idea against the tool: compose checks every `${VAR:?}` in a file whether or not that service is wanted (the Grafana password already behaves this way). With the app's required keys in `docker-compose.yml`, starting only the infrastructure would demand all of them.
2. **A shell script that runs the containers.** Rejected: it would repeat what compose already does (order, networks, health) without its tests.
3. **A second file, `docker-compose.app.yml`, used beside the first.** Chosen: `docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build`. The infrastructure alone is unchanged and asks for nothing new.

## Decision

- **Five services:** `flight-search`, `booking`, `notification`, `disruption-agent`, `website`, built from the Dockerfiles of ADR-0081 and ADR-0082, on the same network as Postgres, Kafka, Redis and Mailpit. Services reach each other by name (`http://booking:8082`).
- **Only the website is published (port 3000).** The services and the agent are reachable from each other, not from outside, so the website is the one way in, as the design intends. A test fails if another app service publishes a port.
- **Kafka gets a second listener.** It advertised only `localhost:9092`, which a container cannot use. It now also advertises `kafka:19092`, which the containers use; `localhost:9092` is unchanged for services run from an IDE.
- **Order by health, not by start.** The three Java services wait for Postgres and Kafka to be healthy; the website waits for Redis, flight-search, booking and the agent. The notification service waits for Mailpit to have started.
- **A health check for every part,** none of which needs a tool the image lacks: the Java services ask their management port with `bash` and `/dev/tcp` (the runtime image has `bash`, not `curl`), the agent calls its `/healthz` with Python, and the website calls a new `/healthz` route with Node. The website's route says only that the site is answering and nothing about what is behind it, so a slow backend never gets the site restarted for something a restart cannot fix.
- **Keys come from the environment and a missing one stops the start.** Every key, token and password is `${NAME:?hint}`. Nothing in the file holds a value. The first administrator (`SKYHUB_BOOTSTRAP_ADMIN_*`) is required too, because without one nobody can sign in to the operations page. The Groq key is the one optional value; with no key the rule-based planner is used (ADR-0080).
- **The agent's ops account** (`SKYHUB_AGENT_USERNAME`/`PASSWORD`) must exist in booking for the agent to do anything, but the agent starts without it, so the stack is healthy before the account is created (`docs/TESTING-LOCALLY.md` shows how).
- **Guard tests** read the real resolved configuration with `docker compose config` (skipped without Docker, run in CI): all five parts and the infrastructure are present, each has a health check, the Java services wait for a healthy database and broker, no secret-looking variable holds a literal value (each is checked against a sentinel put in a throwaway env file), only the website publishes a port, every build folder and Dockerfile exists, a missing key refuses to start, and the infrastructure alone still starts without the app's keys.

## Consequences

- Positive: one command starts all of SkyHub, and the same file is a start for deployment (the dependency order and health checks are what an orchestrator needs).
- Positive: the existing live checks work against it, which is the strongest evidence that the wiring is right (below).
- Negative: **session cookies are `Secure` in production mode**, which the container uses. Chrome and Firefox accept them on `http://localhost`; Safari does not, so sign-in on the website would fail there. Not tried in any browser (below).
- Negative: **the first start builds five images** (the website runs a full `next build`), so it takes several minutes; later starts use the cache.
- Negative: the services' own ports are not published, so Prometheus (the observability profile, which scrapes `host.docker.internal`) does not see the containers yet. Pointing it at them is its own change.
- Negative: development passwords for Postgres stay as they are in the infrastructure file (`skyhub`/`skyhub`); this is a development and demonstration stack, not a production one (no TLS, one broker, one database server, no backups).
- Not done: Kong or any gateway, Kubernetes and Helm, a registry, image scanning, and running the browser (Playwright) tests against the containers.

## Verification

Run on the author's machine with Docker 29, under a separate compose project name with throwaway keys, then torn down with its volumes (the author's own containers and volumes were not touched):

- All nine containers (the five parts, Postgres, Kafka, Redis, Mailpit) reached healthy in dependency order; only the website had a published port.
- `/healthz` answered 200, the home page 200, and a search for DXB to LHR through the website returned SK001 from the real data.
- The **account journey** of `tools/live-check` passed 41 of 41 checks and the **booking journey** 83 of 83, through the real chain (booking, outbox, Kafka on the internal listener, notification, SMTP, Mailpit). For these, a scratch override outside the repository published the three service ports and widened the check-in window the way `stack.py` does (ADR-0059).
- The booking journey was run five times on the same stack: 83, 82, 83, 83, 83 of 83. **The one miss was not captured and not reproduced**, so its cause is unknown; the journey is designed for a fresh stack and that run followed another on the same data. It should be looked at if it shows up again.
- 28 guard tests (images and compose) pass; the frontend has 581 of 581 tests, a clean typecheck and clean lint.

Not verified: any click-through in a browser (sign-in cookies in particular), the website's pages for staff and the gate against these containers, the rebooking assistant through the containerised agent (its ops account was never created), the observability profile against the containers, and the CI job (it runs when the pull request opens).
