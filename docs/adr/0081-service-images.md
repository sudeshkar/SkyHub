# ADR-0081: Container images for the Java services

**Status:** Accepted
**Date:** 2026-11-06

## Context

Every service runs from an IDE or `./mvnw spring-boot:run`. No service had a Dockerfile, so nothing could be deployed, and the roadmap's "Gateway and deployment" slice (compose for the whole stack, Kubernetes, a gateway) had no first step. This slice makes the three Java services into images and nothing more. The disruption agent, the website and a compose profile that starts everything are separate slices.

## Options considered

1. **A Dockerfile per service.** Rejected: three copies of the same recipe that drift apart (a security fix made in two of them).
2. **Build the jar on the host, copy it in.** Rejected: the image then depends on what is on the author's machine, and CI would need a different path from a laptop.
3. **Buildpacks (`spring-boot:build-image`).** Rejected for now: it hides the choices an interviewer asks about (user, layers, memory flags), and needs the Docker socket during the Maven build.
4. **One parameterised multi-stage Dockerfile, built from the repository root.** Chosen.

## Decision

- `docker/service.Dockerfile`, with `--build-arg SERVICE=flight-search|booking|notification`. Built from the repository root, because the parent pom and `api-specs/` (the controllers are generated from them) are needed.
- **Two stages.** The JDK, Maven and sources are in the build stage only; the final image is `eclipse-temurin:25-jre` plus the service. The poms are copied before the sources so a code change does not download every dependency again.
- **Layered.** The jar is extracted with Spring Boot's layer tool: dependencies, loader, snapshot dependencies, the service's own code, in that order. A rebuild changes only the small top layer.
- **Not root.** A fixed user and group (id 10001), so a mounted volume can be given to it. A flaw in a service does not give the container to the attacker.
- **No secret in an image.** `.dockerignore` keeps `.env`, `.git`, `.claude`, build output and the other services out of the build context, and the Dockerfile sets no key, password or token. Keys arrive as environment variables when a container starts (`SKYHUB_PII_KEY` and the others in `.env.example`). Anything in a layer can be read by anyone who can pull the image, so this is the rule the guard tests enforce.
- **Memory.** `-XX:MaxRAMPercentage=75` sizes the heap from the container's limit, and `-XX:+ExitOnOutOfMemoryError` makes a service that has run out of memory stop, so an orchestrator restarts it instead of leaving it half alive.
- **Tests are not run in the image build.** CI runs `mvnw verify` first; the image job compiles only the main code.
- **CI** gets an `images` job: the guard tests (`docker/checks`, no Docker needed), then a real `docker build` of each service. The build is the test that the recipe works.

## Consequences

- Positive: any service becomes an image with one command, the same on a laptop and in CI, and the next slices (compose for everything, Kubernetes) have something to run.
- Positive: the guard tests fail the build if someone adds `.env` to the context, a key to the image, or drops the non-root user.
- Negative: **no health check inside the image.** The runtime image has no `curl`, and a probe belongs to whatever runs the container (compose, Kubernetes), against the management port (9081, 9082, 9083). The next slice adds it.
- Negative: **base images are pinned by major version (`25-jdk`, `25-jre`), not by digest.** A rebuild can pick up a newer patch release, which is wanted for security fixes, but it makes two builds of the same commit differ. Pinning by digest with automated updates is left for the deployment slice.
- Negative: the images are about 420 to 430 MB each. That is the JRE and the dependencies; a smaller runtime (a `jlink` image) would save little against the work and is not done.
- Negative: the build downloads Maven and the dependencies on first use, so a first CI build is slow (the dependency layer and a cache mount make the next ones fast).
- Not done: the disruption agent and website images, a compose profile that starts the whole stack, image scanning, signing, and pushing to a registry.

## Verification

Run on the author's machine with Docker 29: all three images built; each runs as uid 10001 and contains no `.env` file; `flight-search` was started in a container against a throwaway PostgreSQL, started in about 7 seconds as that user, and its management port answered health `UP` (HTTP 200).

Not verified: `booking` and `notification` were built and inspected but not started (they need Kafka, keys and, for notification, an SMTP server); the CI job has not run yet; a search through the containerised `flight-search` was not tried.
