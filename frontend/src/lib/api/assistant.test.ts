import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { askTravellerAssistant, assistantConfigured } from "./assistant";

const TOKEN = "a".repeat(40);
const SESSION = { locator: "YUFHBD", token: "eyJhbGciOiJIUzI1NiJ9.manage.signature" };
const ANSWER = {
  reply: "Online check-in opens 24 hours before departure.",
  route: "help",
  outcome: "answered",
  sources: [{ article: "checking-in-online", title: "Checking in online" }],
};

function respond(status: number, body: unknown, headers: Record<string, string> = {}) {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } }),
  );
}

describe("askTravellerAssistant", () => {
  beforeEach(() => {
    vi.stubEnv("SKYHUB_ASSISTANT_API_TOKEN", TOKEN);
    vi.stubEnv("SKYHUB_ASSISTANT_API_URL", "http://assistant.test:8085");
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("asks with the shared token and the conversation, and never caches the answer", async () => {
    const fetchMock = respond(200, ANSWER);
    vi.stubGlobal("fetch", fetchMock);

    const outcome = await askTravellerAssistant("When can I check in?", [{ role: "traveller", text: "Hi" }], null);

    expect(outcome).toEqual({ kind: "ok", answer: ANSWER });
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("http://assistant.test:8085/api/v1/assistant/messages");
    expect(init.method).toBe("POST");
    expect(init.cache).toBe("no-store");
    expect(init.headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(JSON.parse(init.body)).toEqual({ message: "When can I check in?", history: [{ role: "traveller", text: "Hi" }] });
  });

  it("sends the open booking in its two headers and never in the body", async () => {
    const fetchMock = respond(200, ANSWER);
    vi.stubGlobal("fetch", fetchMock);

    await askTravellerAssistant("Am I checked in?", [], SESSION);

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers["X-Booking-Reference"]).toBe("YUFHBD");
    expect(init.headers["X-Booking-Token"]).toBe(SESSION.token);
    expect(init.body).not.toContain(SESSION.token);
    expect(init.body).not.toContain("YUFHBD");
  });

  it("sends no booking headers when no booking is open", async () => {
    const fetchMock = respond(200, ANSWER);
    vi.stubGlobal("fetch", fetchMock);

    await askTravellerAssistant("Am I checked in?", [], null);

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).not.toHaveProperty("X-Booking-Reference");
    expect(init.headers).not.toHaveProperty("X-Booking-Token");
  });

  it("reports a busy assistant or a model that is down with how long to wait", async () => {
    vi.stubGlobal("fetch", respond(503, { detail: "busy" }, { "Retry-After": "30" }));

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({ kind: "busy", retryAfterSeconds: 30 });
  });

  it("waits a few seconds when a busy answer gives no Retry-After", async () => {
    vi.stubGlobal("fetch", respond(503, { detail: "busy" }));

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({ kind: "busy", retryAfterSeconds: 5 });
  });

  it("passes on why a message was refused", async () => {
    vi.stubGlobal("fetch", respond(400, { detail: "message must be text of 1 to 1000 characters" }));

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({
      kind: "rejected",
      detail: "message must be text of 1 to 1000 characters",
    });
  });

  it.each([401, 500, 404])("treats %s as the assistant being unavailable, a setup problem the traveller cannot fix", async (status) => {
    vi.stubGlobal("fetch", respond(status, { detail: "secret internals" }));

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({ kind: "unavailable" });
  });

  it("treats a network failure or a timeout as unavailable", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")));

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({ kind: "unavailable" });
  });

  it("does not call anything without the shared token", async () => {
    vi.stubEnv("SKYHUB_ASSISTANT_API_TOKEN", "");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    expect(await askTravellerAssistant("Hi", [], null)).toEqual({ kind: "unavailable" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("assistantConfigured", () => {
  afterEach(() => vi.unstubAllEnvs());

  it("is true only when the shared token is set", () => {
    vi.stubEnv("SKYHUB_ASSISTANT_API_TOKEN", TOKEN);
    expect(assistantConfigured()).toBe(true);
    vi.stubEnv("SKYHUB_ASSISTANT_API_TOKEN", "");
    expect(assistantConfigured()).toBe(false);
  });
});
