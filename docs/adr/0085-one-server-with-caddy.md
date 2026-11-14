# ADR-0085: One server with Caddy in front: the first public deployment

**Status:** Accepted
**Date:** 2026-11-11

## Context

SkyHub runs only on the author's machine. To show it to people who will not install Docker (recruiters, engineers asked for feedback), it needs a public address with HTTPS. ADR-0083 already starts the whole stack with one command, but that file is for a laptop: Postgres, Redis, Kafka and Mailpit are published on every interface, the database password is `skyhub`, the website answers plain HTTP on 3000, and emailed links point at `localhost`.

The author has deployed static sites on Vercel and nothing with servers. The answer has to be cheap, understandable on a first try, and not undo the design (only the website faces the public, ADR-0018).

## Options considered

1. **The website on Vercel, the rest on a managed platform (Render, Railway, Fly.io).** Rejected: the services would have to be reachable from Vercel over the internet, so they would need public addresses and a new way to authenticate the website, which is the opposite of ADR-0018. Kafka, Postgres and Redis as managed add-ons cost more than the whole server below, and it is three dashboards to learn instead of one.
2. **Kubernetes (k3s) with Helm.** Rejected for now: the right tool for many machines and rolling deployments, but a lot to learn at once for one machine, and it is still listed on the roadmap.
3. **One rented Linux server running the existing compose files, plus a third file for the server, with Caddy in front.** Chosen: it reuses ADR-0083 as it is, costs one small monthly bill (Hetzner, about €7-8 for 8 GB), and every piece can be seen with `docker compose ps`.

For the web server in front, Caddy over nginx with certbot: Caddy fetches and renews the Let's Encrypt certificate itself from a two-line file, with nothing to schedule.

## Decision

- **`docker-compose.prod.yml`**, used on top of the other two (`deploy/compose.sh` adds all three):
  - **Caddy is the only thing on the internet**: 80 (redirects to HTTPS) and 443 (TCP, and UDP for HTTP/3). `deploy/Caddyfile` proxies the domain in `SKYHUB_DOMAIN` to `website:3000` and sends `Strict-Transport-Security`.
  - **Every other port is removed, not just firewalled** (`ports: !reset []`). Docker writes its own iptables rules and goes around ufw, so a published port would be public even with the server's firewall on. The website is no longer published either: it is reached through Caddy.
  - **Mailpit's inbox is published on loopback only** (`127.0.0.1:8025`), to be opened through an SSH tunnel. It shows whatever addresses visitors typed, so it is never public. Emails never leave the server, so a visitor's real address receives nothing.
  - **`SKYHUB_TRUST_PROXY=true` on the website.** Every request now comes from Caddy, so without it all visitors would share one rate-limit bucket (about 60 searches a minute for the whole internet). Trusting `X-Forwarded-For` is safe here and only here: Caddy (2.5 and later, with no `trusted_proxies`) replaces whatever a visitor sends with the address it saw.
  - **The database password comes from `.env`** (`POSTGRES_PASSWORD`) and is given to the three Java services as `DB_PASSWORD`. **`SKYHUB_SITE_URL` is required**, so emailed links lead to the public site and not to `localhost`.
  - **Memory ceilings**: 768 MB for booking, flight-search and Kafka (heap 384 MB), 512 MB for notification. The Java images size the heap from the container (`MaxRAMPercentage=75`, ADR-0081); without a limit each would size it from the whole server.
  - **Everything restarts after a reboot** (`restart: unless-stopped` on the infrastructure and Caddy; the app services already had it).
  - A missing domain, site address or database password stops the start, like every other key.
- **Three scripts in `deploy/`**, so a first deployment is three commands: `make-env.sh <domain>` writes `.env` with a fresh random value for every key and refuses to overwrite one (a new `SKYHUB_PII_KEY` would make saved bookings unreadable); `compose.sh` is `docker compose` with the three files; `create-agent-account.sh` creates the agent's OPS account from inside the agent's container (booking is not published), with the passwords on standard input and not on a command line. It can be run twice.
- **`docs/DEPLOYING.md`** walks through it on a new Hetzner server for someone who has not done it before: SSH key, server type, cloud firewall, a domain or `sslip.io`, swap, the commands, everyday operations, backups and what to do when something is wrong.
- **Images are built on the server** (`up -d --build`), not pulled from a registry. Simpler for one server; it needs the RAM headroom and takes 10 to 15 minutes the first time.
- **Guard tests** (`docker/checks/test_deploy.py`, read from the resolved configuration like ADR-0083's): only Caddy listens on non-loopback addresses and only on 80 and 443; Postgres, Redis and Kafka publish nothing; Mailpit only on loopback; the website is not published and Caddy waits for it to be healthy; the website trusts the proxy header; the database password and site address come from the environment; each Java service has a memory limit; everything restarts; a missing domain, site address or database password refuses to start.

## Consequences

- Positive: SkyHub can be shown at a real HTTPS address without changing a line of the services, and the design (one way in) holds on the internet.
- Positive: the laptop setup is unchanged; the server file only removes and adds.
- Negative: **one machine, one of everything**: one database with no replica, one Kafka broker, no automatic backups (DEPLOYING.md gives the `pg_dumpall` command), no monitoring from outside the server, and a deployment restarts the changed containers (seconds of errors). Right for a demonstration, not for running an airline.
- Negative: the observability stack is not started on the server, to keep it small. Adding it needs Grafana behind Caddy with a password and Prometheus pointed at the containers (ADR-0083 noted the second).
- Negative: building on the server needs 8 GB and time; a registry with images built in CI would fix both and is on the roadmap.
- Negative: `POSTGRES_PASSWORD` is read only when the database is first created. Changing it later needs changing it inside Postgres too (the file says so).
- Follow-up: a Kong gateway, Kubernetes and Helm, and images built in CI and pulled from a registry stay on the roadmap under *Gateway and deployment*.

## Verification

Run on the author's Windows machine (Docker Desktop, Compose 2.40) from a copy of the branch, under a separate compose project with a `.env` written by `make-env.sh localhost` (Caddy then uses its own local certificate authority instead of Let's Encrypt), then torn down with its volumes. The author's own containers and volumes were not touched.

- `./deploy/compose.sh up -d --build` built the five images and started eleven containers, all healthy, in dependency order, with Caddy last.
- `https://localhost/` and `/healthz` answered 200 with `Via: 1.1 Caddy`, `Strict-Transport-Security` and HTTP/3 offered; `http://localhost/` answered 308 to HTTPS; a search for DXB to LHR through HTTPS returned SK001 from the real data.
- Nothing answered on 5432, 6379, 9092, 1025, 8081, 8082, 8084 or 3000 from the host; the Mailpit inbox answered on 127.0.0.1:8025.
- `create-agent-account.sh` created `agent.ops` the first time and said it already existed the second time.
- **Rate limiting behind the proxy:** 70 searches from one machine, each claiming a different `X-Forwarded-For`, gave 55 answers and then 429s; one more with yet another claimed address got 429 (the header was replaced, not trusted); a search from a second machine (a container on the same network) got 200 (visitors are counted apart).
- 41 guard tests pass (`python -m pytest docker/checks`).

Not verified: a real Hetzner server, a real domain and a Let's Encrypt certificate (the one part only the internet can test), a browser click-through of booking and staff sign-in over HTTPS, the Groq planner on the server, a reboot of the server, and the CI job (it runs when the pull request opens).
