/** What the website's tracing is told to do (ADR-0079). Read once at start-up from the same settings the Java services use. */
export type TracingConfig =
  | { enabled: false }
  | { enabled: true; serviceName: string; endpoint: string; sampling: number; propagateTo: string[] };

const SERVICE_NAME = "skyhub-website";
const DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces";

/**
 * Tracing is **off unless `TRACING_EXPORT_ENABLED` is `true`**, as in the Java services, so a laptop with no observability stack is not
 * flooded with export errors. `OTLP_TRACES_ENDPOINT` and `TRACE_SAMPLING` are the Java services' own settings, with their defaults.
 *
 * <p>A diagnostic must never be the reason the website does not start: an endpoint that is not an http address turns tracing off with a
 * warning (that never repeats the value, since an address can carry a password), and a sampling that is not a number traces everything.
 * The backend addresses are the ones the site calls them at (see lib/api), because the trace is passed on only to those.
 */
export function tracingConfig(env: Record<string, string | undefined>, warn: (message: string) => void = console.warn): TracingConfig {
  if (env.TRACING_EXPORT_ENABLED?.toLowerCase() !== "true") {
    return { enabled: false };
  }
  const endpoint = env.OTLP_TRACES_ENDPOINT?.trim() || DEFAULT_ENDPOINT;
  if (!isHttpAddress(endpoint)) {
    warn("OTLP_TRACES_ENDPOINT is not an http or https address, so tracing is off");
    return { enabled: false };
  }
  return {
    enabled: true,
    serviceName: SERVICE_NAME,
    endpoint,
    sampling: samplingOf(env.TRACE_SAMPLING, warn),
    propagateTo: [
      env.SKYHUB_SEARCH_API_URL ?? "http://localhost:8081",
      env.SKYHUB_BOOKING_API_URL ?? "http://localhost:8082",
      env.SKYHUB_AGENT_API_URL ?? "http://localhost:8084",
    ],
  };
}

function samplingOf(value: string | undefined, warn: (message: string) => void): number {
  if (value === undefined || value.trim() === "") return 1;
  const ratio = Number(value);
  if (Number.isNaN(ratio)) {
    warn("TRACE_SAMPLING is not a number, so every request is traced");
    return 1;
  }
  return Math.min(1, Math.max(0, ratio));
}

function isHttpAddress(value: string): boolean {
  try {
    const { protocol } = new URL(value);
    return protocol === "http:" || protocol === "https:";
  } catch {
    return false;
  }
}
