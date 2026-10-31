import { beforeEach, describe, expect, it, vi } from "vitest";
import { getProposals } from "@/lib/api/agent";
import { openStaffSession, rebookFlights, recordDisruption as recordCall, withdrawDisruption as withdrawCall } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { clearStaffSession, readStaffSession, saveStaffSession } from "@/lib/staff-session";
import { loadProposals, logOut, moveBookings, recordDisruption, staffLogin, withdrawDisruption } from "./actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { staffLogin: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/staff-session", () => ({
  readStaffSession: vi.fn(),
  saveStaffSession: vi.fn(),
  clearStaffSession: vi.fn(),
}));
vi.mock("@/lib/api/booking", () => ({
  openStaffSession: vi.fn(), recordDisruption: vi.fn(), rebookFlights: vi.fn(), withdrawDisruption: vi.fn(),
}));
vi.mock("@/lib/api/agent", () => ({ getProposals: vi.fn() }));
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

describe("loadProposals", () => {
  const SET = {
    planner: "first-fit", flightNumber: "SK501", departureDate: "2026-11-02", bookingsTotal: 1, rebooked: 1,
    proposals: [{ locator: "ABC234", action: "REBOOK" as const, flightNumber: "SK503", departureDate: "2026-11-02",
      segmentIndex: 0, passengerCount: 2, rationale: "Earliest flight that fits." }],
  };

  beforeEach(() => {
    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
  });

  it("asks the agent for proposals for the flight, as staff, and hands them to the page", async () => {
    vi.mocked(getProposals).mockResolvedValue({ kind: "ok", set: SET });

    const result = await loadProposals({ status: "idle" }, form({ flightNumber: " sk501 ", departureDate: "2026-11-02" }));

    expect(getProposals).toHaveBeenCalledWith("SK501", "2026-11-02");
    expect(result).toEqual({ status: "loaded", set: SET });
  });

  it.each([
    ["a bad flight number", { flightNumber: "../x", departureDate: "2026-11-02" }],
    ["a bad date", { flightNumber: "SK501", departureDate: "soon" }],
  ])("refuses %s without calling the agent", async (_name, fields) => {
    const result = await loadProposals({ status: "idle" }, form(fields));

    expect(result.status).toBe("error");
    expect(getProposals).not.toHaveBeenCalled();
  });

  it("asks a signed-out person to sign in, and keeps staff-only work behind the login", async () => {
    vi.mocked(readStaffSession).mockResolvedValue(null);

    expect(await loadProposals({ status: "idle" }, form({ flightNumber: "SK501", departureDate: "2026-11-02" }))).toEqual({ status: "signed-out" });
    expect(getProposals).not.toHaveBeenCalled();
  });

  it.each([
    [{ kind: "busy" as const }, "busy"],
    [{ kind: "rejected" as const, detail: "The booking service answered 500 for the bookings on SK501" }, "answered 500"],
    [{ kind: "unavailable" as const }, "not responding"],
  ])("explains a failure in words a person can act on", async (outcome, expected) => {
    vi.mocked(getProposals).mockResolvedValue(outcome);

    const result = await loadProposals({ status: "idle" }, form({ flightNumber: "SK501", departureDate: "2026-11-02" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining(expected) });
  });
});

describe("moveBookings", () => {
  const MOVE = { locator: "ABC234", segmentIndex: 0, flightNumber: "SK503", departureDate: "2026-11-02" };

  beforeEach(() => {
    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
  });

  it("moves the approved bookings in one call with the staff member's own token, so the audit names them", async () => {
    vi.mocked(rebookFlights).mockResolvedValue({ kind: "results", results: [
      { locator: "ABC234", segmentIndex: 0, outcome: "MOVED", seats: ["14A", "14B"] },
      { locator: "DEF567", segmentIndex: 1, outcome: "UNCHANGED", seats: ["14C"] }] });

    const result = await moveBookings([MOVE, { ...MOVE, locator: "DEF567", segmentIndex: 1 }]);

    expect(rebookFlights).toHaveBeenCalledTimes(1);
    expect(rebookFlights).toHaveBeenCalledWith(SESSION.token, [MOVE, { ...MOVE, locator: "DEF567", segmentIndex: 1 }]);
    expect(result).toEqual({ status: "done", results: [
      { locator: "ABC234", status: "moved", seats: ["14A", "14B"] }, { locator: "DEF567", status: "unchanged", seats: ["14C"] }] });
  });

  it("reports a refused move with the booking service's reason and the others as they went", async () => {
    vi.mocked(rebookFlights).mockResolvedValue({ kind: "results", results: [
      { locator: "ABC234", segmentIndex: 0, outcome: "REFUSED", detail: "SK503 flies DXB to JFK, not the booked route DXB to LHR" },
      { locator: "DEF567", segmentIndex: 0, outcome: "NOT_FOUND", detail: "No booking matches this reference" },
      { locator: "GHI890", segmentIndex: 0, outcome: "CONFLICT", detail: "The seats changed while this booking was being moved; try again." },
      { locator: "JKL123", segmentIndex: 0, outcome: "MOVED", seats: ["14A"] }] });

    const result = await moveBookings([MOVE, { ...MOVE, locator: "DEF567" }, { ...MOVE, locator: "GHI890" }, { ...MOVE, locator: "JKL123" }]);

    expect(result).toEqual({ status: "done", results: [
      { locator: "ABC234", status: "refused", detail: "SK503 flies DXB to JFK, not the booked route DXB to LHR" },
      { locator: "DEF567", status: "failed", detail: "That booking was not found." },
      { locator: "GHI890", status: "failed", detail: "The seats changed while this booking was being moved; try again." },
      { locator: "JKL123", status: "moved", seats: ["14A"] }] });
  });

  it("keeps what was moved and reports the moves that were not tried when the service stops partway", async () => {
    vi.mocked(rebookFlights).mockResolvedValue({ kind: "results", results: [
      { locator: "ABC234", segmentIndex: 0, outcome: "MOVED", seats: ["14A"] },
      { locator: "DEF567", segmentIndex: 0, outcome: "UNAVAILABLE", detail: "The timetable is not answering." },
      { locator: "GHI890", segmentIndex: 0, outcome: "NOT_ATTEMPTED", detail: "Not tried: an earlier move could not be completed." }] });

    const result = await moveBookings([MOVE, { ...MOVE, locator: "DEF567" }, { ...MOVE, locator: "GHI890" }]);

    // The page stops sending further batches when fewer results than moves come back, so not-tried moves are left out.
    expect(result).toEqual({ status: "done", results: [
      { locator: "ABC234", status: "moved", seats: ["14A"] },
      { locator: "DEF567", status: "failed", detail: "The timetable is not answering." }] });
  });

  it("signs the person out when the token is refused, and says so plainly when the service cannot be reached", async () => {
    vi.mocked(rebookFlights).mockResolvedValueOnce({ kind: "unauthorized" });
    expect(await moveBookings([MOVE])).toEqual({ status: "signed-out", results: [] });
    expect(clearStaffSession).toHaveBeenCalled();

    vi.mocked(rebookFlights).mockResolvedValueOnce({ kind: "unavailable" });
    expect(await moveBookings([MOVE])).toMatchObject({ status: "error", message: expect.stringContaining("not responding") });
  });

  it("shows the service's reason when it refuses the whole set", async () => {
    vi.mocked(rebookFlights).mockResolvedValue({ kind: "rejected", detail: "ABC234 flight 0 is listed twice" });

    expect(await moveBookings([MOVE])).toEqual({ status: "error", message: "ABC234 flight 0 is listed twice" });
  });

  it("refuses more than one batch at a time, and anything that is not a valid move, before changing anything", async () => {
    const tooMany = Array.from({ length: 51 }, (_, i) => ({ ...MOVE, locator: `B${String(i).padStart(5, "0")}` }));

    expect((await moveBookings(tooMany)).status).toBe("error");
    expect((await moveBookings([])).status).toBe("error");
    expect((await moveBookings([{ ...MOVE, locator: "abc" }])).status).toBe("error");
    expect((await moveBookings([{ ...MOVE, segmentIndex: 9 }])).status).toBe("error");
    expect((await moveBookings([{ ...MOVE, flightNumber: "../x" }])).status).toBe("error");
    expect((await moveBookings([{ ...MOVE, departureDate: "soon" }])).status).toBe("error");
    expect(rebookFlights).not.toHaveBeenCalled();
  });

  it("asks a signed-out person to sign in", async () => {
    vi.mocked(readStaffSession).mockResolvedValue(null);

    expect(await moveBookings([MOVE])).toEqual({ status: "signed-out", results: [] });
    expect(rebookFlights).not.toHaveBeenCalled();
  });

  it("slows a flood of requests", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 30 });

    expect((await moveBookings([MOVE])).status).toBe("error");
    expect(rebookFlights).not.toHaveBeenCalled();
  });
});

describe("withdrawDisruption", () => {
  const request = { flightNumber: "sk501", departureDate: "2026-11-02", confirm: "yes" };

  beforeEach(() => {
    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
  });

  it("withdraws the disruption with the staff member's own token and says how many bookings are on the flight", async () => {
    vi.mocked(withdrawCall).mockResolvedValue({ kind: "withdrawn", affectedBookings: 12 });

    const result = await withdrawDisruption({ status: "idle" }, form(request));

    expect(withdrawCall).toHaveBeenCalledWith(SESSION.token, "SK501", "2026-11-02");
    expect(result).toEqual({ status: "withdrawn", flightNumber: "SK501", departureDate: "2026-11-02", affectedBookings: 12 });
  });

  it("does nothing until the person has ticked the confirmation", async () => {
    const result = await withdrawDisruption({ status: "idle" }, form({ ...request, confirm: "" }));

    expect(result).toMatchObject({ status: "error", message: expect.stringContaining("confirm") });
    expect(withdrawCall).not.toHaveBeenCalled();
  });

  it("refuses a malformed flight or date before calling the service", async () => {
    expect(await withdrawDisruption({ status: "idle" }, form({ ...request, flightNumber: "nope" }))).toMatchObject({ status: "error" });
    expect(await withdrawDisruption({ status: "idle" }, form({ ...request, departureDate: "" }))).toMatchObject({ status: "error" });
    expect(withdrawCall).not.toHaveBeenCalled();
  });

  it("sends the person back to log in when there is no session or the token was refused", async () => {
    vi.mocked(readStaffSession).mockResolvedValue(null);
    expect(await withdrawDisruption({ status: "idle" }, form(request))).toEqual({ status: "signed-out" });

    vi.mocked(readStaffSession).mockResolvedValue(SESSION);
    vi.mocked(withdrawCall).mockResolvedValue({ kind: "unauthorized" });
    expect(await withdrawDisruption({ status: "idle" }, form(request))).toEqual({ status: "signed-out" });
    expect(clearStaffSession).toHaveBeenCalled();
  });

  it("shows the service's reason when there is nothing to withdraw or bookings were moved, and says when it is unreachable", async () => {
    vi.mocked(withdrawCall).mockResolvedValue({ kind: "refused", detail: "Bookings were moved off SK501, so its disruption has to stay on record" });
    expect(await withdrawDisruption({ status: "idle" }, form(request))).toMatchObject({
      status: "error", message: "Bookings were moved off SK501, so its disruption has to stay on record",
    });

    vi.mocked(withdrawCall).mockResolvedValue({ kind: "unavailable" });
    expect(await withdrawDisruption({ status: "idle" }, form(request))).toMatchObject({
      status: "error", message: expect.stringContaining("not responding"),
    });
  });
});
