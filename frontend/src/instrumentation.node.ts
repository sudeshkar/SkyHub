import { ParentBasedSampler, TraceIdRatioBasedSampler } from "@opentelemetry/sdk-trace-base";
import { OTLPHttpJsonTraceExporter, registerOTel } from "@vercel/otel";
import { RedactingSpanExporter } from "@/lib/trace-redaction";
import { tracingConfig } from "@/lib/tracing-config";

/**
 * Starts tracing for the Node server when it is switched on (ADR-0079). Kept out of instrumentation.ts so the Edge runtime never loads it.
 *
 * <p>What it does: names this service {@code skyhub-website}; exports to the same collector the Java services use, **through a wrapper that
 * cuts the query from every address** (some pages carry a one-time token in it); samples by `TRACE_SAMPLING`, keeping a decision made
 * upstream; and passes the trace context on to the backend services by their addresses, so one request is one trace from the browser's
 * request to the database. Its own exporting is not traced.
 */
export function startTracing(env: Record<string, string | undefined>): void {
  const config = tracingConfig(env);
  if (!config.enabled) return;
  registerOTel({
    serviceName: config.serviceName,
    traceExporter: new RedactingSpanExporter(new OTLPHttpJsonTraceExporter({ url: config.endpoint })),
    traceSampler: new ParentBasedSampler({ root: new TraceIdRatioBasedSampler(config.sampling) }),
    instrumentationConfig: { fetch: { propagateContextUrls: config.propagateTo, ignoreUrls: [config.endpoint] } },
  });
}
