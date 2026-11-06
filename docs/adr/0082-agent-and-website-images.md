# ADR-0082: Container images for the disruption agent and the website

**Status:** Accepted
**Date:** 2026-11-06

## Context

ADR-0081 made the three Java services into images. The other two parts of SkyHub, the Python disruption agent and the Next.js website, still had none, so the stack could not be run as containers. This slice adds both, and nothing more: a compose profile that starts everything, health checks and a gateway are the next slices.

## Options considered

1. **Add them to the one Java Dockerfile.** Rejected: the recipes share nothing (a JDK and Maven, Python and pip, Node and npm), so one file would be three recipes behind conditions.
2. **Build from the repository root, as the Java services do.** Rejected: neither needs the parent pom or `api-specs/` (the website's API types are generated and committed), and the root `.dockerignore` would have to know about both.
3. **One Dockerfile each, built from the folder it belongs to, with that folder's own `.dockerignore`.** Chosen.
4. **For the website, `next start` with every dependency installed.** Rejected: a larger image that carries development dependencies and everything that was only needed to build.

## Decision

- **Agent** (`docker/disruption-agent.Dockerfile`, built from `services/disruption-agent`): a build stage installs the agent and its libraries into a virtual environment; the final stage is the same slim Python plus that environment. No pip cache, no sources, no tests. The project file is copied before the code so a code change does not install every library again.
- **Website** (`docker/website.Dockerfile`, built from `frontend`): `next build` in the build stage; the final stage holds only Next.js's traced output (`output: "standalone"`, set in `next.config.ts`), the static files and `public/`. The image has about a dozen packages in `node_modules`, not the project's hundreds.
- **Both run as a user that is not root:** the agent as a fixed id (10001), the website as the `node` user the image already has.
- **Addresses and keys are read when the container starts, never built in.** The website reads `SKYHUB_SEARCH_API_URL`, `SKYHUB_BOOKING_API_URL`, `SKYHUB_AGENT_API_URL`, `SKYHUB_AGENT_API_TOKEN` and `REDIS_URL` at run time (none is a `NEXT_PUBLIC_` value, which would be baked into the bundle), so the same image serves every environment. The agent's token, account and Groq key arrive the same way.
- **The agent listens on `0.0.0.0` inside its container** (`SKYHUB_AGENT_HOST`), because its default `127.0.0.1` cannot be reached from outside one. That is an address, not a secret; whether the port is published is for the compose file or the cluster. Its token still guards every request.
- **Each build folder has its own `.dockerignore`** that keeps `.env` and `.env.*` out (the website's `.env.local` holds this machine's keys), along with virtual environments, `node_modules`, `.next` and the tests.
- **The guard tests now hold every Dockerfile to the same rules:** not root, no key or env file copied in, no key given a value in an `ENV` or `ARG`, secrets kept out of each build context, more than one stage, and a test that fails if a new Dockerfile is added without being listed. CI builds both images in a new job.

## Consequences

- Positive: all five parts of SkyHub can now be built as images, the same on a laptop and in CI.
- Positive: one website image for every environment, with nothing environment-specific inside.
- Negative: **a `NEXT_PUBLIC_` variable added later would be baked in at build time** and so would differ from the run-time settings. The website has none today; the rule is that an address or key must not use that prefix.
- Negative: **the website's build runs in Docker without reaching any backend.** It worked, but a page that needs a backend at build time would break the image build; the pages are rendered when asked, so none does today.
- Negative: **no health check in either image.** The agent has `/healthz`, the website has no health route yet; both belong to compose or Kubernetes (the next slice).
- Negative: as in ADR-0081, base images are pinned by major version and not by digest, and the website image is about 270 MB (the agent's about 176 MB).
- Not done: a compose profile for the whole stack, a health route for the website, image scanning, signing and a registry.

## Verification

Run on the author's machine with Docker 29. Agent: the image built and runs as uid 10001 with no `.env` inside; started without its token it stops and says what is missing; with throwaway values it listened on `0.0.0.0:8084`, `GET /healthz` answered 200, and `POST /v1/proposals` answered 401 with no token and with a wrong one. Website: the image built (the full `next build` ran inside it), runs as `node` with no `.env` inside, served `/` with 200, and served a built JS or CSS file from `/_next/static/` with 200. The 20 guard tests pass.

Not verified: the agent talking to a real booking and flight-search service, the website's pages that call a backend (none was running), the website's rate limiting against Redis, and the CI job (it runs when the pull request opens).
