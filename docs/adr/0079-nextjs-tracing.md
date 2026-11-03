# ADR-0079: Tracing the website

**Status:** Accepted
**Date:** 2026-11-04

## Context

The booking and flight-search services export traces to Tempo (`TRACING_EXPORT_ENABLED`, off by default) and a request that crosses them is one trace. The website, which calls both on every page, was the gap: a slow search page showed two unconnected backend traces and nothing for the page that asked for them. The ROADMAP listed "no Next.js tracing".

Two things make it more than adding a library. **A page's address can carry a one-time secret** (`/account/set-password?token=...`, `/account/confirm-email?token=...`, the undo-email link), and a tracing library records the address of every request as a span attribute, which then sits in Tempo. And **the backend must be told which trace a call belongs to**, or the trace is cut at the website.

## Options considered

1. **Hand-written OpenTelemetry setup.** Rejected: Next.js already names its own spans (route, render, server action), and `@vercel/otel` is the supported way to register them.
2. **Trace but leave the addresses.** Rejected: it would put one-time tokens in a store many people can read.
3. **`@vercel/otel`, off by default, every span passed through a redacting exporter, trace context sent only to our own services.** Chosen.

## Decision

- **Off unless `TRACING_EXPORT_ENABLED=true`**, with the same settings as the Java services: `OTLP_TRACES_ENDPOINT` (default `http://localhost:4318/v1/traces`) and `TRACE_SAMPLING` (0 to 1, default 1). A laptop with no collector is not flooded with errors. An endpoint that is not an http address, or a sampling that is not a number, never stops the site: the first turns tracing off and the second traces everything, each with a warning that does not repeat the value (an address can hold a password).
- **Registered only in the Node server** (`instrumentation.ts` loads `instrumentation.node.ts` when `NEXT_RUNTIME` is `nodejs`), so the exporter and the redaction never run where they were not written for.
- **Every span goes through `RedactingSpanExporter` before it leaves the process.** It cuts the query and fragment off every address attribute (`http.url`, `url.full`, `url.original`, `http.target`, `http.request.target`), replaces `url.query`, and in all other text attributes and event attributes (an error's message) hides the value of a parameter named `token`, `code`, `key`, `secret`, `password`, `sig`, `signature` or `authorization`. The path is kept, so a request can still be found. It is a decorator over the real OTLP exporter and holds no other logic.
- **The trace is passed on to our three backends and to nobody else:** `propagateContextUrls` is the addresses the website calls them at (`SKYHUB_SEARCH_API_URL`, `SKYHUB_BOOKING_API_URL`, `SKYHUB_AGENT_API_URL`, with the same defaults as the rest of the code). Third parties are never sent a trace id. The exporter's own request is not traced.
- **Sampling is parent-based over a ratio:** a decision already made upstream is kept, and a request with none is sampled by `TRACE_SAMPLING`.
- **Cost:** work added to each exported span is one pass over its attributes, O(a) with a small. Nothing is added to a request when tracing is off.

## Consequences

- Positive: a page, its server actions and the backend calls they make are one trace in Tempo, and a slow page can be broken down.
- Positive: the secret in an address cannot reach Tempo even if a new page puts one there, because the redaction is by what an attribute is, not by a list of pages.
- Negative: **an address's path is kept, so a locator in the path is in Tempo.** A booking reference (`/manage/ABC234`) is a lookup key, not a secret, but it is visible to anyone who can read traces. Trace access is therefore as restricted as the logs.
- Negative: **redaction is by name.** A secret in a parameter called something else is not hidden. A new parameter that holds a secret must be added to the list in `trace-redaction.ts`, with a test.
- Negative: the redaction changes the span's attributes in place before export, which relies on the SDK handing the exporter the live attributes. It is checked against the real SDK (below), and would have to be rechecked after an upgrade of the SDK.
- Negative: `@vercel/otel` needs several OpenTelemetry packages installed alongside it, and `sdk-trace-base` is a package whose newer releases are described as moving; they are pinned by the lock file.
- Not done: browser-side tracing (only the server is traced), metrics and logs export from the website, and a trace id in the website's own log lines.

## Verification

Unit tests for the settings (off by default and for any value but "true", defaults, the same names as the Java services, sampling clamped, a bad sampling or endpoint never fatal and never echoed), for the redaction (queries cut, secrets scrubbed from attributes and event attributes, other values and non-text left alone, shutdown and flush passed on) and for the start-up (nothing starts when off or on the edge, the redacting exporter is the one used, only our backends get the trace, the exporter is not traced, the sampler is parent-based).

`frontend/scripts/trace-check.mjs` (run by hand after `npm run build`; not in CI because it starts the server) starts the built site against a stub collector and a stub backend and **passed all seven checks**: spans arrive named `skyhub-website`; a one-time token in `/account/set-password?token=...` is in no exported byte; the path is; the backend received a `traceparent` whose trace id is one the collector received; and with tracing off nothing is exported. It was **not** run against a real Tempo and Grafana, and the Tempo side (how the trace looks) was not seen. The whole frontend ran 579 tests, `tsc`, `eslint` and `next build` clean; **three unrelated tests (the manage-view seat test and the two large proposals-panel tests) time out when the machine is busy and pass alone and with fewer workers**; this predates this slice and is not fixed here.
