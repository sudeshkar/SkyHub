import { describe, expect, it, vi } from "vitest";
import { tracingConfig } from "./tracing-config";

describe("tracingConfig (ADR-0079)", () => {
  it("is off unless it is asked for, so a laptop with no observability stack is not flooded with export errors", () => {
    expect(tracingConfig({})).toEqual({ enabled: false });
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "false" })).toEqual({ enabled: false });
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "1" })).toEqual({ enabled: false });   // only the word "true", as for the Java services
  });

  it("is on when asked, with the same defaults the Java services have", () => {
    const config = tracingConfig({ TRACING_EXPORT_ENABLED: "true" });

    expect(config).toMatchObject({ enabled: true, serviceName: "skyhub-website", endpoint: "http://localhost:4318/v1/traces", sampling: 1 });
  });

  it("reads the setting in any case", () => {
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "TRUE" }).enabled).toBe(true);
  });

  it("takes the collector's address and the sampling from the same settings the Java services use", () => {
    const config = tracingConfig({ TRACING_EXPORT_ENABLED: "true", OTLP_TRACES_ENDPOINT: "https://tempo.internal:4318/v1/traces", TRACE_SAMPLING: "0.25" });

    expect(config).toMatchObject({ endpoint: "https://tempo.internal:4318/v1/traces", sampling: 0.25 });
  });

  it("passes the trace on to the backend services it calls, by the addresses it calls them at", () => {
    const defaults = tracingConfig({ TRACING_EXPORT_ENABLED: "true" });
    const custom = tracingConfig({
      TRACING_EXPORT_ENABLED: "true", SKYHUB_SEARCH_API_URL: "http://search:8081", SKYHUB_BOOKING_API_URL: "http://booking:8082", SKYHUB_AGENT_API_URL: "http://agent:8084",
    });

    expect(defaults).toMatchObject({ propagateTo: ["http://localhost:8081", "http://localhost:8082", "http://localhost:8084"] });
    expect(custom).toMatchObject({ propagateTo: ["http://search:8081", "http://booking:8082", "http://agent:8084"] });
  });

  it("keeps the sampling between none and all", () => {
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "true", TRACE_SAMPLING: "5" })).toMatchObject({ sampling: 1 });
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "true", TRACE_SAMPLING: "-2" })).toMatchObject({ sampling: 0 });
    expect(tracingConfig({ TRACING_EXPORT_ENABLED: "true", TRACE_SAMPLING: "0" })).toMatchObject({ sampling: 0 });
  });

  it("says so and traces everything when the sampling is not a number, rather than failing the site", () => {
    const warn = vi.fn();

    const config = tracingConfig({ TRACING_EXPORT_ENABLED: "true", TRACE_SAMPLING: "lots" }, warn);

    expect(config).toMatchObject({ enabled: true, sampling: 1 });
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("TRACE_SAMPLING"));
  });

  it.each(["not a url", "ftp://tempo/v1/traces", "tempo:4318"])("turns tracing off, with a warning that never repeats the value, for an endpoint that is not an http address (%s)", (bad) => {
    const warn = vi.fn();

    const config = tracingConfig({ TRACING_EXPORT_ENABLED: "true", OTLP_TRACES_ENDPOINT: bad }, warn);

    expect(config).toEqual({ enabled: false });   // a diagnostic must never be the reason the website does not start
    expect(warn).toHaveBeenCalledWith(expect.stringContaining("OTLP_TRACES_ENDPOINT"));
    expect(String(warn.mock.calls[0][0])).not.toContain(bad);   // an address can carry a password
  });
});
