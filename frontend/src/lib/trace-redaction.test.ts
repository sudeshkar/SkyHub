import { describe, expect, it, vi } from "vitest";
import { redactUrl, RedactingSpanExporter, scrubText } from "./trace-redaction";

describe("redactUrl (ADR-0079)", () => {
  it("drops the query and the fragment of an address, because a one-time token can be in it", () => {
    expect(redactUrl("http://localhost:3000/account/set-password?token=SECRET&x=1#frag")).toBe("http://localhost:3000/account/set-password?[redacted]");
    expect(redactUrl("/account/confirm-email?token=SECRET")).toBe("/account/confirm-email?[redacted]");
  });

  it("leaves an address with no query as it is", () => {
    expect(redactUrl("http://localhost:8082/api/v1/pnrs/ABC234")).toBe("http://localhost:8082/api/v1/pnrs/ABC234");
    expect(redactUrl("")).toBe("");
  });
});

describe("scrubText", () => {
  it("hides the value of a parameter that holds a secret wherever the text came from", () => {
    expect(scrubText("GET /x?token=abc123&page=2 failed; also ?code=zzz and &password=hunter2 and ?key=k")).toBe(
      "GET /x?token=[redacted]&page=2 failed; also ?code=[redacted] and &password=[redacted] and ?key=[redacted]",
    );
  });

  it("leaves text without one alone", () => {
    expect(scrubText("a token was refused")).toBe("a token was refused");
  });
});

type FakeSpan = { name: string; attributes: Record<string, unknown>; events: { name: string; attributes?: Record<string, unknown> }[] };

const span = (attributes: Record<string, unknown>, events: FakeSpan["events"] = []): FakeSpan => ({ name: "GET /account/set-password", attributes, events });

describe("RedactingSpanExporter (ADR-0079)", () => {
  function exportThrough(spans: FakeSpan[]) {
    const inner = { export: vi.fn((_spans: unknown, done: (r: { code: number }) => void) => done({ code: 0 })), shutdown: vi.fn().mockResolvedValue(undefined), forceFlush: vi.fn().mockResolvedValue(undefined) };
    const done = vi.fn();
    new RedactingSpanExporter(inner as never).export(spans as never, done);
    return { inner, done };
  }

  it("removes the query from every attribute that holds an address before anything leaves the process", () => {
    const s = span({
      "http.url": "http://localhost:3000/account/set-password?token=SECRET", "url.full": "http://localhost:3000/account/set-password?token=SECRET",
      "http.target": "/account/set-password?token=SECRET", "url.query": "token=SECRET", "http.method": "GET", "http.status_code": 200,
    });

    exportThrough([s]);

    expect(JSON.stringify(s)).not.toContain("SECRET");
    expect(s.attributes["http.url"]).toBe("http://localhost:3000/account/set-password?[redacted]");
    expect(s.attributes["url.query"]).toBe("[redacted]");
    expect(s.attributes["http.method"]).toBe("GET");       // everything else is untouched
    expect(s.attributes["http.status_code"]).toBe(200);
  });

  it("scrubs a secret out of any other text attribute and out of the attributes of events such as an error", () => {
    const s = span({ "next.span_name": "render /account?token=SECRET" }, [{ name: "exception", attributes: { "exception.message": "failed for /account/undo-email-change?token=SECRET" } }]);

    exportThrough([s]);

    expect(JSON.stringify(s)).not.toContain("SECRET");
  });

  it("hands the spans on to the real exporter, with its answer", () => {
    const { inner, done } = exportThrough([span({ "http.url": "http://x/y" })]);

    expect(inner.export).toHaveBeenCalledTimes(1);
    expect(done).toHaveBeenCalledWith({ code: 0 });
  });

  it("does not touch attributes that are not text, and copes with a span that has none", () => {
    const s = { name: "x", attributes: undefined, events: undefined } as unknown as FakeSpan;
    const numbers = span({ "http.status_code": 200, "flag": true, "sizes": [1, 2] });

    expect(() => exportThrough([s, numbers])).not.toThrow();
    expect(numbers.attributes).toEqual({ "http.status_code": 200, flag: true, sizes: [1, 2] });
  });

  it("passes shutdown and flush on", async () => {
    const inner = { export: vi.fn(), shutdown: vi.fn().mockResolvedValue(undefined), forceFlush: vi.fn().mockResolvedValue(undefined) };
    const exporter = new RedactingSpanExporter(inner as never);

    await exporter.shutdown();
    await exporter.forceFlush();

    expect(inner.shutdown).toHaveBeenCalled();
    expect(inner.forceFlush).toHaveBeenCalled();
  });
});
