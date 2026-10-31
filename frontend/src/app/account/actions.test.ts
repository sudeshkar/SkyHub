import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  openAccountSession,
  requestPasswordLink as requestCall,
  setAccountPassword as setCall,
} from "@/lib/api/booking";
import { clearAccountSession, saveAccountSession } from "@/lib/account-session";
import { rateLimit } from "@/lib/rate-limit";
import { requestLink, setPassword, signIn, signOut } from "./actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { accountLink: {}, accountSignIn: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({
  readAccountSession: vi.fn(),
  saveAccountSession: vi.fn(),
  clearAccountSession: vi.fn(),
}));
vi.mock("@/lib/api/booking", () => ({ openAccountSession: vi.fn(), requestPasswordLink: vi.fn(), setAccountPassword: vi.fn() }));
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
});

describe("requestLink", () => {
  it("asks for a link and answers the same whether or not the address has an account", async () => {
    vi.mocked(requestCall).mockResolvedValue({ kind: "sent" });

    const result = await requestLink({ status: "idle" }, form({ email: " Priya@Example.com " }));

    expect(requestCall).toHaveBeenCalledWith("priya@example.com");
    expect(result).toEqual({ status: "sent", email: "priya@example.com" });
  });

  it("does not call the service for something that cannot be an address", async () => {
    const result = await requestLink({ status: "idle" }, form({ email: "nope" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("valid email") });
    expect(requestCall).not.toHaveBeenCalled();
  });

  it("stops a script from flooding inboxes before it reaches the service", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 600 });

    const result = await requestLink({ status: "idle" }, form({ email: "priya@example.com" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("10 minutes") });
    expect(requestCall).not.toHaveBeenCalled();
  });

  it("shows the service's reason, and says when it is unreachable", async () => {
    vi.mocked(requestCall).mockResolvedValue({ kind: "rejected", detail: "Enter a valid email address" });
    expect(await requestLink({ status: "idle" }, form({ email: "priya@example.com" }))).toMatchObject({
      status: "error", message: "Enter a valid email address",
    });

    vi.mocked(requestCall).mockResolvedValue({ kind: "unavailable" });
    expect(await requestLink({ status: "idle" }, form({ email: "priya@example.com" }))).toMatchObject({
      status: "error", message: expect.stringContaining("not responding"),
    });
  });
});

describe("setPassword", () => {
  const good = { token: "abc", password: "a long enough passphrase", confirm: "a long enough passphrase" };

  it("sets the password with the emailed token", async () => {
    vi.mocked(setCall).mockResolvedValue({ kind: "set" });

    expect(await setPassword({ status: "idle" }, form(good))).toEqual({ status: "done" });
    expect(setCall).toHaveBeenCalledWith("abc", "a long enough passphrase");
  });

  it("does not call the service when the two passwords differ or the password is too short", async () => {
    expect(await setPassword({ status: "idle" }, form({ ...good, confirm: "something else entirely" }))).toMatchObject({
      status: "error", message: expect.stringContaining("match"),
    });
    expect(await setPassword({ status: "idle" }, form({ ...good, password: "short", confirm: "short" }))).toMatchObject({
      status: "error", message: expect.stringContaining("12"),
    });
    expect(setCall).not.toHaveBeenCalled();
  });

  it("refuses a password the hashing cannot store before calling the service, counting bytes as the service does", async () => {
    for (const tooLong of ["a".repeat(73), "é".repeat(37)]) {
      expect(await setPassword({ status: "idle" }, form({ ...good, password: tooLong, confirm: tooLong }))).toMatchObject({
        status: "error", message: expect.stringContaining("72"),
      });
    }
    expect(setCall).not.toHaveBeenCalled();
  });

  it("accepts the longest password the hashing can store", async () => {
    vi.mocked(setCall).mockResolvedValue({ kind: "set" });
    const longest = "é".repeat(36); // 36 characters but exactly 72 bytes

    expect(await setPassword({ status: "idle" }, form({ ...good, password: longest, confirm: longest }))).toEqual({ status: "done" });
    expect(setCall).toHaveBeenCalledWith("abc", longest);
  });

  it("says the link has stopped working, and asks for a new one, when the service refuses the token", async () => {
    vi.mocked(setCall).mockResolvedValue({ kind: "invalid-link" });

    expect(await setPassword({ status: "idle" }, form(good))).toEqual({ status: "link-expired" });
  });

  it("shows why a password was refused and says when the service is unreachable", async () => {
    vi.mocked(setCall).mockResolvedValue({ kind: "rejected", detail: "A password needs 12 to 100 characters" });
    expect(await setPassword({ status: "idle" }, form(good))).toMatchObject({ status: "error", message: "A password needs 12 to 100 characters" });

    vi.mocked(setCall).mockResolvedValue({ kind: "unavailable" });
    expect(await setPassword({ status: "idle" }, form(good))).toMatchObject({ status: "error", message: expect.stringContaining("not responding") });
  });
});

describe("signIn", () => {
  it("opens a session, keeps the token in the cookie and moves to the account page", async () => {
    vi.mocked(openAccountSession).mockResolvedValue({
      kind: "opened", accessToken: "t".repeat(40), expiresAt: "2026-11-02T10:30:00Z", email: "priya@example.com",
    });

    await expect(signIn({ status: "idle" }, form({ email: " Priya@Example.com ", password: "a long enough passphrase" })))
      .rejects.toThrow("NEXT_REDIRECT:/account");
    expect(openAccountSession).toHaveBeenCalledWith("priya@example.com", "a long enough passphrase");
    expect(saveAccountSession).toHaveBeenCalledWith({ email: "priya@example.com", token: "t".repeat(40) }, "2026-11-02T10:30:00Z");
  });

  it("gives one message however the service refused, keeps the address, and stores nothing", async () => {
    vi.mocked(openAccountSession).mockResolvedValue({ kind: "denied" });

    const result = await signIn({ status: "idle" }, form({ email: "priya@example.com", password: "whatever whatever" }));

    expect(result).toEqual({ status: "error", message: "Wrong email or password.", email: "priya@example.com" });
    expect(saveAccountSession).not.toHaveBeenCalled();
  });

  it("does not call the service when a field is empty, and stops guessing before it reaches the service", async () => {
    expect(await signIn({ status: "idle" }, form({ email: "", password: "" }))).toMatchObject({ status: "error" });
    expect(openAccountSession).not.toHaveBeenCalled();

    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 600 });
    expect(await signIn({ status: "idle" }, form({ email: "a@b.co", password: "whatever whatever" }))).toMatchObject({
      status: "error", message: expect.stringContaining("Too many attempts"),
    });
    expect(openAccountSession).not.toHaveBeenCalled();
  });

  it("says so when the booking service is down", async () => {
    vi.mocked(openAccountSession).mockResolvedValue({ kind: "unavailable" });

    expect(await signIn({ status: "idle" }, form({ email: "a@b.co", password: "whatever whatever" }))).toMatchObject({
      status: "error", message: expect.stringContaining("not responding"),
    });
  });
});

describe("signOut", () => {
  it("drops the token and goes back to the sign-in page", async () => {
    await expect(signOut()).rejects.toThrow("NEXT_REDIRECT:/account");
    expect(clearAccountSession).toHaveBeenCalled();
  });
});
