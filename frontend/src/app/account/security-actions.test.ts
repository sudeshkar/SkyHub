import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { endAllAccountSessions } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { signOutEverywhere } from "./security-actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn(), clearAccountSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({ endAllAccountSessions: vi.fn() }));
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const TOKEN = "a".repeat(40);

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(readAccountSession).mockResolvedValue({ email: "priya@example.com", token: TOKEN });
});

describe("signOutEverywhere", () => {
  it("ends every session with the account token, drops this browser's cookie and shows the sign-in page", async () => {
    vi.mocked(endAllAccountSessions).mockResolvedValue({ kind: "ended" });

    await expect(signOutEverywhere()).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(endAllAccountSessions).toHaveBeenCalledWith(TOKEN);
    expect(clearAccountSession).toHaveBeenCalledTimes(1);
  });

  it("does nothing for a person who is not signed in", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);

    await expect(signOutEverywhere()).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(endAllAccountSessions).not.toHaveBeenCalled();
  });

  it("treats a session the service no longer accepts as already ended: there is nothing to end, and the cookie goes", async () => {
    vi.mocked(endAllAccountSessions).mockResolvedValue({ kind: "unauthorized" });

    await expect(signOutEverywhere()).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(clearAccountSession).toHaveBeenCalledTimes(1);
  });

  it("keeps the cookie and says so when the service cannot be reached, so nobody is told they are signed out when they are not", async () => {
    vi.mocked(endAllAccountSessions).mockResolvedValue({ kind: "unavailable" });

    await expect(signOutEverywhere()).rejects.toThrow("NEXT_REDIRECT:/account?problem=signout");

    expect(clearAccountSession).not.toHaveBeenCalled();
  });

  it("slows a flood of requests before it reaches the service", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 60 });

    await expect(signOutEverywhere()).rejects.toThrow("NEXT_REDIRECT:/account?problem=wait");

    expect(endAllAccountSessions).not.toHaveBeenCalled();
    expect(clearAccountSession).not.toHaveBeenCalled();
  });
});
