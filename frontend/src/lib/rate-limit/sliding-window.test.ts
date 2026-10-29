import { describe, expect, it } from "vitest";
import { createRateLimiter, MemoryCounterStore, type Policy } from "./sliding-window";

const POLICY: Policy = { name: "test", limit: 3, windowMs: 60_000 };

function limiterAt(clock: { now: number }) {
  return createRateLimiter(new MemoryCounterStore(), () => clock.now);
}

describe("sliding window rate limiter", () => {
  it("allows requests up to the limit, then refuses with a retry time", async () => {
    const clock = { now: 0 };
    const check = limiterAt(clock);

    for (let i = 0; i < 3; i++) expect(await check(POLICY, "client-a")).toEqual({ allowed: true });
    expect(await check(POLICY, "client-a")).toEqual({ allowed: false, retryAfterSeconds: 60 });
  });

  it("counts each client separately", async () => {
    const check = limiterAt({ now: 0 });

    for (let i = 0; i < 4; i++) await check(POLICY, "client-a");

    expect(await check(POLICY, "client-b")).toEqual({ allowed: true });
  });

  it("does not allow a double burst across the window edge", async () => {
    const clock = { now: 59_000 };
    const check = limiterAt(clock);
    for (let i = 0; i < 3; i++) await check(POLICY, "client-a");

    clock.now = 61_000; // a fixed window would reset here and allow 3 more at once
    expect(await check(POLICY, "client-a")).toMatchObject({ allowed: false });
  });

  it("frees capacity gradually as the previous window slides out", async () => {
    const clock = { now: 0 };
    const check = limiterAt(clock);
    for (let i = 0; i < 3; i++) await check(POLICY, "client-a");

    clock.now = 60_000 + 50_000; // previous window now weighs 1/6: estimate 0.5 + 1
    expect(await check(POLICY, "client-a")).toEqual({ allowed: true });
  });
});
