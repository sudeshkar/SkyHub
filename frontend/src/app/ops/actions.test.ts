import { beforeEach, describe, expect, it, vi } from "vitest";
import { openStaffSession, recordDisruption as recordCall } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { clearStaffSession, readStaffSession, saveStaffSession } from "@/lib/staff-session";
import { logOut, recordDisruption, staffLogin } from "./actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { staffLogin: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/staff-session", () => ({
  readStaffSession: vi.fn(),
  saveStaffSession: vi.fn(),
  clearStaffSession: vi.fn(),
}));
vi.mock("@/lib/api/booking", () => ({ openStaffSession: vi.fn(), recordDisruption: vi.fn() }));
// redirect() works by throwing; the real one is not available outside a request.
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const SESSION = { username: "sam.ops", role: "OPS" as const, token: "t".repeat(40) };

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
});

describe("staffLogin", () => {
  it("opens a session, keeps the token in the cookie and moves to the ops page", async () => {
    vi.mocked(openStaffSession).mockResolvedValue({
      kind: "opened", accessToken: SESSION.token, expiresAt: "2026-10-30T10:30:00Z", username: "sam.ops", role: "OPS",
    });

    await expect(staffLogin({ status: "idle" }, form({ username: " Sam.Ops ", password: "a-long-passphrase" })))
      .rejects.toThrow("NEXT_REDIRECT:/ops");

    expect(openStaffSession).toHaveBeenCalledWith("sam.ops", "a-long-passphrase");
    expect(saveStaffSession).toHaveBeenCalledWith(SESSION, "2026-10-30T10:30:00Z");
  });

  it("gives one message for a wrong password and an unknown user, keeps the name, and stores nothing", async () => {
    vi.mocked(openStaffSession).mockResolvedValue({ kind: "denied" });

    const result = await staffLogin({ status: "idle" }, form({ username: "sam.ops", password: "nope" }));

    expect(result).toEqual({ status: "error", message: "Wrong username or password.", username: "sam.ops" });
    expect(saveStaffSession).not.toHaveBeenCalled();
  });

  it("does not call the service when a field is empty", async () => {
    const result = await staffLogin({ status: "idle" }, form({ username: "sam.ops", password: "" }));

    expect(result.status).toBe("error");
    expect(openStaffSession).not.toHaveBeenCalled();
  });

  it("says so when the booking service is down", async () => {
    vi.mocked(openStaffSession).mockResolvedValue({ kind: "unavailable" });

    const result = await staffLogin({ status: "idle" }, form({ username: "sam.ops", password: "pw-pw-pw-pw" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("not responding") });
  });

  it("stops guessing before it reaches the service", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 600 });

    const result = await staffLogin({ status: "idle" }, form({ username: "sam.ops", password: "pw-pw-pw-pw" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("10 minutes") });
    expect(openStaffSession).not.toHaveBeenCalled();
  });
});

describe("logOut", () => {
  it("drops the token and goes back to the login", async () => {
    await expect(logOut()).rejects.toThrow("NEXT_REDIRECT:/ops");
    expect(clearStaffSession).toHaveBeenCalled();
  });
});

describe("recordDisruption", () => {
  const delay = {
    flightNumber: "sk501", departureDate: "2026-11-02", type: "DELAYED",
    revisedDeparture: "2026-11-02T14:30", reason: "  Late inbound aircraft ",
  };

  beforeEach(() => {
    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
  });

  it("records a delay, treating the entered time as UTC, and reports how many bookings it touches", async () => {
    vi.mocked(recordCall).mockResolvedValue({ kind: "recorded", affectedBookings: 42 });

    const result = await recordDisruption({ status: "idle" }, form(delay));

    expect(recordCall).toHaveBeenCalledWith(SESSION.token, {
      flightNumber: "SK501", departureDate: "2026-11-02", type: "DELAYED",
      revisedDeparture: "2026-11-02T14:30:00Z", reason: "Late inbound aircraft",
    });
    expect(result).toEqual({
      status: "recorded", flightNumber: "SK501", departureDate: "2026-11-02", type: "DELAYED", affectedBookings: 42,
    });
  });

  it("records a cancellation without a revised time", async () => {
    vi.mocked(recordCall).mockResolvedValue({ kind: "recorded", affectedBookings: 0 });

    await recordDisruption({ status: "idle" }, form({ ...delay, type: "CANCELLED", revisedDeparture: "2026-11-02T14:30" }));

    expect(recordCall).toHaveBeenCalledWith(SESSION.token, {
      flightNumber: "SK501", departureDate: "2026-11-02", type: "CANCELLED", reason: "Late inbound aircraft",
    });
  });

  it.each([
    ["a bad flight number", { flightNumber: "../x" }],
    ["a bad date", { departureDate: "02/11/2026" }],
    ["an unknown type", { type: "EXPLODED" }],
    ["a delay with no new time", { revisedDeparture: "" }],
    ["a reason that is too long", { reason: "x".repeat(201) }],
  ])("refuses %s without calling the service, and keeps what was typed", async (_name, change) => {
    const result = await recordDisruption({ status: "idle" }, form({ ...delay, ...change }));

    expect(result.status).toBe("error");
    expect(recordCall).not.toHaveBeenCalled();
    if (result.status === "error") expect(result.values.flightNumber).toBeDefined();
  });

  it("sends the person back to log in when there is no session or the token was refused", async () => {
    vi.mocked(readStaffSession).mockResolvedValue(null);
    expect(await recordDisruption({ status: "idle" }, form(delay))).toEqual({ status: "signed-out" });

    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
    vi.mocked(recordCall).mockResolvedValue({ kind: "unauthorized" });
    expect(await recordDisruption({ status: "idle" }, form(delay))).toEqual({ status: "signed-out" });
    expect(clearStaffSession).toHaveBeenCalled();
  });

  it("shows the service's reason when it refuses the request, and says when it is unreachable", async () => {
    vi.mocked(recordCall).mockResolvedValue({ kind: "rejected", detail: "A delay needs a revised departure time" });
    expect(await recordDisruption({ status: "idle" }, form(delay))).toMatchObject({
      status: "error", message: "A delay needs a revised departure time",
    });

    vi.mocked(recordCall).mockResolvedValue({ kind: "unavailable" });
    expect(await recordDisruption({ status: "idle" }, form(delay))).toMatchObject({
      status: "error", message: expect.stringContaining("not responding"),
    });
  });
});
