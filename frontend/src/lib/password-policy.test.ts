import { describe, expect, it } from "vitest";
import { passwordProblem, PASSWORD_RULE } from "./password-policy";

/** The same cases as the booking service's PasswordPolicyTest (ADR-0048): the page must never promise what the service refuses. */
describe("passwordProblem", () => {
  it("accepts twelve characters and up to seventy two bytes", () => {
    expect(passwordProblem("a".repeat(12))).toBeNull();
    expect(passwordProblem("a".repeat(72))).toBeNull();
    expect(passwordProblem("correct horse battery staple")).toBeNull();
  });

  it("refuses a password under twelve characters", () => {
    for (const tooShort of ["", "short", "elevenchars"]) expect(passwordProblem(tooShort)).toBe(PASSWORD_RULE);
  });

  it("refuses seventy three bytes, which the hashing cannot store", () => {
    expect(passwordProblem("a".repeat(73))).toBe(PASSWORD_RULE);
    expect(passwordProblem("a".repeat(5_000))).toBe(PASSWORD_RULE);
  });

  it("counts bytes, not characters, for the upper limit", () => {
    expect(passwordProblem("é".repeat(36))).toBeNull(); // 72 bytes
    expect(passwordProblem("é".repeat(37))).toBe(PASSWORD_RULE); // 74 bytes, far fewer than 72 characters
    expect(passwordProblem("あ".repeat(24))).toBeNull(); // 72 bytes
    expect(passwordProblem("あ".repeat(25))).toBe(PASSWORD_RULE);
  });

  it("counts characters, not bytes, for the lower limit, and a symbol of two UTF-16 units as one", () => {
    expect(passwordProblem("é".repeat(12))).toBeNull();
    expect(passwordProblem("é".repeat(11))).toBe(PASSWORD_RULE);
    expect(passwordProblem("😀".repeat(12))).toBeNull();
    expect(passwordProblem("😀".repeat(11))).toBe(PASSWORD_RULE);
    expect(passwordProblem("😀".repeat(18))).toBeNull(); // 72 bytes
    expect(passwordProblem("😀".repeat(19))).toBe(PASSWORD_RULE); // 76 bytes
  });

  it("judges the password exactly as given: nothing is trimmed and spaces count", () => {
    expect(passwordProblem("elevenchars ")).toBeNull();
    expect(passwordProblem(" ".repeat(12))).toBeNull();
  });

  it("states the whole rule in one message, the same one the service gives", () => {
    expect(PASSWORD_RULE).toBe("A password needs 12 to 72 characters, counting accents and symbols as more than one (72 bytes at most)");
  });
});
