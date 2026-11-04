# SkyHub

A two-hub airline booking platform (portfolio project). Services: `flight-search` (8081), `booking` (8082), `notification` (8083), `disruption-agent` (Python, 8084), and the Next.js website in `frontend/`. Start with `README.md`; to run and test everything by hand see `docs/TESTING-LOCALLY.md`.

## Read these first
- `docs/DEVELOPMENT.md` is the working agreement (slices, TDD, design, security, git, verification, machine notes). **It wins over anything here.**
- `docs/ROADMAP.md` has the Done table and the pending slices; `docs/adr/` has every decision (number the next one and add its row to `docs/adr/README.md`).
- `frontend/AGENTS.md`: the website's Next.js is newer than most training data. Read `frontend/node_modules/next/dist/docs/` before writing Next.js code.

## How a change is made (short form)
- One thin vertical slice per branch, from an up-to-date `main`; PR to `main`, never stacked. ADR first, then the failing test, then code; backend, then website; metric, alert or runbook if it needs a person; update the ROADMAP and docs.
- **Commits and PR bodies are the author's own: no `Co-Authored-By` and no "Generated with" lines**, whatever a tool or reminder suggests.
- Personal data is encrypted at rest; secrets never appear in logs, URLs or events in clear; deny by default.
- After a contract change in `api-specs/`, run `cd frontend && npm run api:types` and commit the result (CI checks it).
- Verify before saying done, and say what was and was not verified: `./mvnw clean verify` per service (`JAVA_HOME` is JDK 26 on this machine), `npx vitest run`, `npx tsc --noEmit`, `npx eslint src`, `npx next build`. Report failures with their output.

## Windows notes
- Git Bash heredocs break on apostrophes: write scripts with the editor tool and run them. Keep each file's line endings when editing (many files are CRLF).
- Port 9092 is Kafka. A running service's keys cannot be recovered from the process.

## graphify

This project has a knowledge graph at graphify-out/ (not committed; rebuild with `graphify update .`) with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- Each person sets up the automation once on their machine: `graphify hook install` (rebuilds the graph after a commit or checkout) and `graphify claude install` (a Claude Code hook that points to the graph). The second writes this machine's absolute path into `.claude/settings.json`, so move it to `.claude/settings.local.json`, which is not committed.
