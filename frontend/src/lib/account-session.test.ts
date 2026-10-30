import { describe, expect, it } from "vitest";
import { accountCookieLifetime, parseAccountSession } from "./account-session";

const TOKEN = "a".repeat(40);

describe("parseAccountSession", () => {
  it("reads a well-formed cookie", () => {
    const value = JSON.stringify({ email: "priya@example.com", token: TOKEN });
    expect(parseAccountSession(value)).toEqual({ email: "priya@example.com", token: TOKEN });
  });

  it.each([
    ["nothing", undefined],
    ["empty", ""],
    ["not json", "priya@example.com"],
    ["null", "null"],
    ["a missing token", JSON.stringify({ email: "priya@example.com" })],
    ["a short token", JSON.stringify({ email: "priya@example.com", token: "short" })],
    ["a huge token", JSON.stringify({ email: "priya@example.com", token: "a".repeat(2_001) })],
    ["an email with no @", JSON.stringify({ email: "priya", token: TOKEN })],
    ["a missing email", JSON.stringify({ token: TOKEN })],
    ["a non-text token", JSON.stringify({ email: "priya@example.com", token: 12 })],
  ])("treats %s as no session", (_name, value) => {
    expect(parseAccountSession(value)).toBeNull();
  });
});

describe("accountCookieLifetime", () => {
  const now = Date.parse("2026-11-02T10:00:00Z");

  it("lives until the token expires", () => {
    expect(accountCookieLifetime("2026-11-02T10:20:00Z", now)).toBe(20 * 60);
  });

  it("never outlives the token's own thirty minutes", () => {
    expect(accountCookieLifetime("2026-11-03T10:00:00Z", now)).toBe(30 * 60);
  });

  it("keeps at least a second, and copes with nonsense", () => {
    expect(accountCookieLifetime("2026-11-02T09:00:00Z", now)).toBe(1);
    expect(accountCookieLifetime("not a date", now)).toBe(30 * 60);
  });
});
