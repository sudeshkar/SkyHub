# SkyHub — Tech Stack

Every choice has a **why** and a **why not the alternative**. Be ready to defend each one in an interview.

## Backend (Phase 1)

| Concern | Choice | Why | Why not the alternative |
|---|---|---|---|
| Language | **Java 25 (LTS)** | Current long-term-support release; records, sealed types, pattern matching, virtual threads | Java 17/21 are still common, but 25 shows you track the platform |
| Framework | **Spring Boot 4.1** | Enterprise standard; matches Emirates' Java estate | Quarkus/Micronaut start faster but are far less common in large enterprises |
| Web | **Spring MVC** (+ virtual threads) | Simple blocking model; virtual threads give high concurrency without reactive complexity | WebFlux adds reactive complexity we don't need |
| API design | **OpenAPI 3 + openapi-generator** | API-first: the contract is written and reviewed before code; generated interfaces keep code and spec in sync | Code-first (annotations → spec) lets the spec drift from intent |
| Persistence | **Spring Data JPA (Hibernate)** | Productive for aggregate-style CRUD; `@Version` gives optimistic locking | jOOQ/JDBC give more SQL control — consider for reporting later |
| Database | **PostgreSQL 18** | ACID transactions — mandatory for seat inventory; one database per service | MongoDB lacks the multi-row transactional guarantees bookings need |
| Migrations | **Flyway** | Versioned, reviewable SQL; schema owned by migrations, not Hibernate | `ddl-auto=update` is unsafe in production |
| Cache | **Redis 8** (Spring Cache) | Shared across service instances; TTL support | Caffeine is in-process only — every instance holds its own copy |
| Validation | **Jakarta Bean Validation** | Declarative; generated from OpenAPI constraints | — |
| Errors | **ProblemDetail (RFC 9457)** | Standard, machine-readable error format | Ad-hoc `{ "error": "..." }` bodies |
| Build | **Maven (multi-module) + wrapper** | Most common build tool in enterprise Java; the JD lists it first | Gradle is fine too — pick one and know it well |

## Testing (TDD)

| Concern | Choice | Why |
|---|---|---|
| Unit tests | **JUnit Jupiter + AssertJ** | Fluent, readable assertions |
| Mocks | **Mockito** | Isolate services from repositories |
| Web tests | **`@WebMvcTest` + MockMvc** | Controllers, validation and error mapping without starting a server |
| DB tests | **`@DataJpaTest` + Testcontainers (PostgreSQL)** | Test against the real database engine, not an imitation |
| Architecture rules | **ArchUnit** | Enforce "domain has no Spring imports" as a failing test |
| Coverage | **JaCoCo** | Coverage gate in CI |
| Load test | **JMeter** | Prove the no-double-booking guarantee and search latency under load |

## DevOps & Quality

| Concern | Choice | Why |
|---|---|---|
| Source control | **Git + GitHub** (public) | Recruiters can see it. GitLab CI is the equivalent if you want the JD's exact tool |
| CI | **GitHub Actions** | Free for public repos; build → test → quality gate → image |
| Code quality | **SonarQube Cloud** (free for public repos) | Named in the JD; quality gate in CI |
| Containers | **Docker** (multi-stage builds) + **Docker Compose** for local dev | One command runs everything |
| Orchestration (Phase 4) | **Kubernetes (kind locally) + Helm** | JD lists Kubernetes/OpenShift; kind is free |
| Cloud (Phase 4) | **AWS** (free tier / small EC2 or ECS) | Most-requested cloud in the JD |

## Integration & Observability (Phases 2–4)

| Concern | Choice | Why |
|---|---|---|
| API gateway | **Kong (OSS)** | Named in the JD; auth, rate limiting, routing |
| Messaging | **Apache Kafka 4 (KRaft)** | Event-driven side effects (notifications, loyalty); no ZooKeeper |
| Resilience | **Resilience4j** | Circuit breakers, retries and timeouts between services |
| Metrics | **Micrometer → Prometheus → Grafana** | Industry standard |
| Tracing | **OpenTelemetry** | Vendor-neutral; same idea as AppDynamics |
| Logs | **Structured JSON logs + Loki** | Splunk-style search; correlation IDs across services |

## Frontend (Phase 2)

| Concern | Choice | Why |
|---|---|---|
| Framework | **Next.js (App Router) + React + TypeScript** | JD lists React/Next.js |
| Styling | **Tailwind CSS** | Fast, consistent UI |
| Tests | **Jest + React Testing Library**, **Playwright** for end-to-end | JD lists Jest; Playwright is the modern Selenium |

## AI (Phase 5)

| Concern | Choice | Why |
|---|---|---|
| LLM provider | **Groq** (free tier, OpenAI-compatible API) | Fast inference with tool calling; no cost |
| Models | A large model (e.g. `openai/gpt-oss-120b` or `llama-3.3-70b-versatile`) for the agent; a small one (e.g. `llama-3.1-8b-instant`) for simple tasks | Saves free-tier quota. Check console.groq.com/docs/models — the lineup changes |
| Agent service | **Python 3.13 + FastAPI** | Matches Emirates' move toward Python/AI |
| Agent framework | **LangGraph + `langchain-groq`** | Tool calling and step-by-step flows |
| Tool protocol | **MCP server** exposing SkyHub APIs | Agentic-AI standard |
| Tests | **pytest** with a fake LLM client + an on-demand **eval suite** | Unit tests never spend quota; evals measure real quality |

AI design rules:
- The model sits behind an `LlmClient` interface (port and adapter). Changing provider = changing base URL, model and key.
- Retry with exponential backoff on HTTP 429; cache repeated calls in development.
- The API key lives in `.env` (git-ignored) locally and in GitHub Actions secrets in CI. Commit only `.env.example`.

## Not using (and why)

- **GraphQL** for the public API — REST + OpenAPI is the enterprise default and matches the JD's API-first wording.
- **Many microservices on day 1** — start with two; split only when there is a reason.
- **NoSQL for bookings** — seat inventory needs ACID transactions.
