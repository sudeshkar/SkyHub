import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const registerOTel = vi.fn();
vi.mock("@vercel/otel", () => ({
  registerOTel: (options: unknown) => registerOTel(options),
  OTLPHttpJsonTraceExporter: class {
    constructor(public config: unknown) {}
  },
}));

beforeEach(() => {
  registerOTel.mockClear();
  vi.stubEnv("NEXT_RUNTIME", "nodejs");
  vi.stubEnv("TRACING_EXPORT_ENABLED", "");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

async function register() {
  const { register: run } = await import("./instrumentation");
  await run();
}

describe("register (ADR-0079)", () => {
  it("starts no tracing at all unless it is switched on", async () => {
    await register();

    expect(registerOTel).not.toHaveBeenCalled();
  });

  it("starts none outside the Node server, where the exporter and the redaction are not meant to run", async () => {
    vi.stubEnv("TRACING_EXPORT_ENABLED", "true");
    vi.stubEnv("NEXT_RUNTIME", "edge");

    await register();

    expect(registerOTel).not.toHaveBeenCalled();
  });

  it("starts tracing as the website, exporting through the redacting exporter to the collector", async () => {
    vi.stubEnv("TRACING_EXPORT_ENABLED", "true");
    vi.stubEnv("OTLP_TRACES_ENDPOINT", "http://tempo:4318/v1/traces");

    await register();

    expect(registerOTel).toHaveBeenCalledTimes(1);
    const options = registerOTel.mock.calls[0][0];
    expect(options.serviceName).toBe("skyhub-website");
    expect(options.traceExporter.constructor.name).toBe("RedactingSpanExporter");   // never the bare exporter: addresses can hold a token
  });

  it("passes the trace on to the backend services and to nobody else, and does not trace its own exporting", async () => {
    vi.stubEnv("TRACING_EXPORT_ENABLED", "true");
    vi.stubEnv("OTLP_TRACES_ENDPOINT", "http://tempo:4318/v1/traces");

    await register();

    const fetch = registerOTel.mock.calls[0][0].instrumentationConfig.fetch;
    expect(fetch.propagateContextUrls).toEqual(["http://localhost:8081", "http://localhost:8082", "http://localhost:8084"]);
    expect(fetch.ignoreUrls).toEqual(["http://tempo:4318/v1/traces"]);
  });

  it("samples by the setting, keeping a decision already made upstream", async () => {
    vi.stubEnv("TRACING_EXPORT_ENABLED", "true");
    vi.stubEnv("TRACE_SAMPLING", "0.5");

    await register();

    const sampler = registerOTel.mock.calls[0][0].traceSampler;
    expect(sampler.toString()).toContain("ParentBased");
    expect(sampler.toString()).toContain("0.5");
  });
});
