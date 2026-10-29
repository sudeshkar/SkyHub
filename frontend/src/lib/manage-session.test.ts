import { describe, expect, it } from "vitest";
import { cookieLifetime, parseSession } from "./manage-session";

const TOKEN = "a".repeat(40);

describe("parseSession", () => {
  it("reads a well-formed cookie", () => {
    expect(parseSession(JSON.stringify({ locator: "ABC234", token: TOKEN }))).toEqual({ locator: "ABC234", token: TOKEN });
  });

  it.each([
    ["nothing", undefined],
    ["empty", ""],
    ["not json", "ABC234"],
    ["a json string", JSON.stringify("ABC234")],
    ["null", "null"],
    ["a lowercase locator", JSON.stringify({ locator: "abc234", token: TOKEN })],
    ["a short locator", JSON.stringify({ locator: "ABC", token: TOKEN })],
    ["a missing token", JSON.stringify({ locator: "ABC234" })],
    ["a tiny token", JSON.stringify({ locator: "ABC234", token: "x" })],
    ["an enormous token", JSON.stringify({ locator: "ABC234", token: "a".repeat(5_000) })],
    ["a numeric token", JSON.stringify({ locator: "ABC234", token: 12345 })],
  ])("treats %s as no session", (_name, value) => {
    expect(parseSession(value)).toBeNull();
  });
});

describe("cookieLifetime", () => {
  const now = Date.parse("2026-10-29T10:00:00Z");

  it("lasts until the token expires", () => {
    expect(cookieLifetime("2026-10-29T10:10:00Z", now)).toBe(600);
  });

  it("never outlives the 15-minute token", () => {
    expect(cookieLifetime("2026-10-29T12:00:00Z", now)).toBe(900);
  });

  it("is at least one second, even for a token that is already expired", () => {
    expect(cookieLifetime("2026-10-29T09:00:00Z", now)).toBe(1);
  });

  it("falls back to the maximum for an unreadable date", () => {
    expect(cookieLifetime("soon", now)).toBe(900);
  });
});
