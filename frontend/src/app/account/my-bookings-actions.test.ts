import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { openMyBookingSession } from "@/lib/api/booking";
import { saveSession } from "@/lib/manage-session";
import { rateLimit } from "@/lib/rate-limit";
import { openMyBooking } from "./my-bookings-actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/account-session", () => ({ readAccountSession: vi.fn(), clearAccountSession: vi.fn() }));
vi.mock("@/lib/manage-session", () => ({ saveSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({ openMyBookingSession: vi.fn() }));
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const ACCOUNT_TOKEN = "a".repeat(40);

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(readAccountSession).mockResolvedValue({ email: "priya@example.com", token: ACCOUNT_TOKEN });
});

describe("openMyBooking", () => {
  it("opens the booking with the account token and hands the booking's own token to the manage page", async () => {
    vi.mocked(openMyBookingSession).mockResolvedValue({
      kind: "opened", accessToken: "m".repeat(40), expiresAt: "2026-11-02T10:15:00Z",
    });

    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/manage");

    expect(openMyBookingSession).toHaveBeenCalledWith(ACCOUNT_TOKEN, "ABC234");
    expect(saveSession).toHaveBeenCalledWith({ locator: "ABC234", token: "m".repeat(40) }, "2026-11-02T10:15:00Z");
  });

  it("sends a person who is not signed in to the sign-in page without calling the service", async () => {
    vi.mocked(readAccountSession).mockResolvedValue(null);

    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(openMyBookingSession).not.toHaveBeenCalled();
  });

  it("does not call the service for something that cannot be a booking reference", async () => {
    await expect(openMyBooking("not-a-ref")).rejects.toThrow("NEXT_REDIRECT:/account?problem=open");

    expect(openMyBookingSession).not.toHaveBeenCalled();
  });

  it("drops an account session the service has stopped accepting", async () => {
    vi.mocked(openMyBookingSession).mockResolvedValue({ kind: "unauthorized" });

    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/account");

    expect(clearAccountSession).toHaveBeenCalled();
    expect(saveSession).not.toHaveBeenCalled();
  });

  it("says so, and stores nothing, when the booking is not this account's or the service is down", async () => {
    vi.mocked(openMyBookingSession).mockResolvedValue({ kind: "not-found" });
    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/account?problem=open");

    vi.mocked(openMyBookingSession).mockResolvedValue({ kind: "unavailable" });
    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/account?problem=open");

    expect(saveSession).not.toHaveBeenCalled();
  });

  it("stops a script from opening bookings in a loop before it reaches the service", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 60 });

    await expect(openMyBooking("ABC234")).rejects.toThrow("NEXT_REDIRECT:/account?problem=wait");

    expect(openMyBookingSession).not.toHaveBeenCalled();
  });
});
