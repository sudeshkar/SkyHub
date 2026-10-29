import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";
import { rateLimitRequest } from "@/lib/rate-limit";
import { proxy } from "./proxy";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { search: {} }, rateLimitRequest: vi.fn() }));

const request = () => new NextRequest("http://localhost:3000/search?origin=CMB&destination=JFK&date=2026-11-02");

describe("proxy", () => {
  it("lets an allowed search through", async () => {
    vi.mocked(rateLimitRequest).mockResolvedValueOnce({ allowed: true });

    const response = await proxy(request());

    expect(response.headers.get("x-middleware-next")).toBe("1");
  });

  it("answers 429 with Retry-After when the client is over the limit", async () => {
    vi.mocked(rateLimitRequest).mockResolvedValueOnce({ allowed: false, retryAfterSeconds: 42 });

    const response = await proxy(request());

    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("42");
    expect(await response.text()).toContain("Please wait 42 seconds");
  });
});
