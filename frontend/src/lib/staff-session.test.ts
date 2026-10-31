import { describe, expect, it } from "vitest";
import { parseStaffSession, staffCookieLifetime } from "./staff-session";

const TOKEN = "a".repeat(40);

describe("parseStaffSession", () => {
  it("reads a well-formed cookie", () => {
    const value = JSON.stringify({ username: "sam.ops", role: "OPS", token: TOKEN });
    expect(parseStaffSession(value)).toEqual({ username: "sam.ops", role: "OPS", token: TOKEN });
  });

  it("accepts the admin role", () => {
    const value = JSON.stringify({ username: "root.admin", role: "ADMIN", token: TOKEN });
    expect(parseStaffSession(value)?.role).toBe("ADMIN");
  });

  it("accepts the gate role, which the booking service issues (ADR-0060): a gate agent is signed in, not signed out", () => {
    const value = JSON.stringify({ username: "gate.one", role: "GATE", token: TOKEN });
    expect(parseStaffSession(value)).toEqual({ username: "gate.one", role: "GATE", token: TOKEN });
  });

  it.each([
    ["nothing", undefined],
    ["empty", ""],
    ["not json", "sam.ops"],
    ["null", "null"],
    ["an unknown role", JSON.stringify({ username: "sam.ops", role: "ROOT", token: TOKEN })],
    ["a missing role", JSON.stringify({ username: "sam.ops", token: TOKEN })],
    ["an upper-case username", JSON.stringify({ username: "Sam.Ops", role: "OPS", token: TOKEN })],
    ["a missing username", JSON.stringify({ role: "OPS", token: TOKEN })],
    ["a tiny token", JSON.stringify({ username: "sam.ops", role: "OPS", token: "x" })],
    ["an enormous token", JSON.stringify({ username: "sam.ops", role: "OPS", token: "a".repeat(5_000) })],
    ["a numeric token", JSON.stringify({ username: "sam.ops", role: "OPS", token: 12345 })],
  ])("treats %s as no session", (_name, value) => {
    expect(parseStaffSession(value)).toBeNull();
  });
});

describe("staffCookieLifetime", () => {
  const now = Date.parse("2026-10-30T10:00:00Z");

  it("lives until the token expires", () => {
    expect(staffCookieLifetime("2026-10-30T10:10:00Z", now)).toBe(600);
  });

  it("never outlives the 30-minute token, even if the clock disagrees", () => {
    expect(staffCookieLifetime("2026-10-30T14:00:00Z", now)).toBe(30 * 60);
  });

  it("is at least one second, and falls back to the maximum for a bad date", () => {
    expect(staffCookieLifetime("2026-10-30T09:00:00Z", now)).toBe(1);
    expect(staffCookieLifetime("not a date", now)).toBe(30 * 60);
  });
});
