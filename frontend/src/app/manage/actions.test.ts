import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  cancelPnr,
  changeSeat as changeSeatCall,
  checkIn as checkInCall,
  openManageSession,
  type Pnr,
} from "@/lib/api/booking";
import { clearSession, readSession, saveSession } from "@/lib/manage-session";
import { rateLimit } from "@/lib/rate-limit";
import { cancelBooking, changeSeat, checkInFlight, closeSession, lookupBooking } from "./actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: { lookup: {}, manage: {} }, rateLimit: vi.fn() }));
vi.mock("@/lib/manage-session", () => ({ readSession: vi.fn(), saveSession: vi.fn(), clearSession: vi.fn() }));
vi.mock("@/lib/api/booking", () => ({
  openManageSession: vi.fn(),
  cancelPnr: vi.fn(),
  changeSeat: vi.fn(),
  checkIn: vi.fn(),
  getSeatMap: vi.fn(),
}));
// redirect() works by throwing; the real one is not available outside a request.
vi.mock("next/navigation", () => ({
  redirect: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  }),
}));

const PNR = { locator: "YUFHBD" } as Pnr;
const SESSION = { locator: "YUFHBD", token: "t".repeat(40) };

function form(locator: string, lastName: string): FormData {
  const data = new FormData();
  data.set("locator", locator);
  data.set("lastName", lastName);
  return data;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(rateLimit).mockResolvedValue({ allowed: true });
});

describe("lookupBooking", () => {
  it("opens a session, stores the token in the cookie and moves to the booking", async () => {
    vi.mocked(openManageSession).mockResolvedValue({
      kind: "opened", accessToken: SESSION.token, expiresAt: "2026-10-29T10:15:00Z", pnr: PNR,
    });

    await expect(lookupBooking({ status: "idle" }, form(" yufhbd ", " Perera "))).rejects.toThrow("NEXT_REDIRECT:/manage");

    expect(openManageSession).toHaveBeenCalledWith("YUFHBD", "Perera");
    expect(saveSession).toHaveBeenCalledWith({ locator: "YUFHBD", token: SESSION.token }, "2026-10-29T10:15:00Z");
  });

  it("gives the same message for an unknown reference and a wrong name, and stores nothing", async () => {
    vi.mocked(openManageSession).mockResolvedValue({ kind: "not-found" });

    const result = await lookupBooking({ status: "idle" }, form("YUFHBD", "Silva"));

    expect(result).toEqual({
      status: "error",
      message: "We couldn't find a booking with that reference and last name.",
      locator: "YUFHBD",
    });
    expect(saveSession).not.toHaveBeenCalled();
  });

  it("rejects a malformed reference without calling the API", async () => {
    const result = await lookupBooking({ status: "idle" }, form("ABC", "Perera"));

    expect(result.status).toBe("error");
    expect(openManageSession).not.toHaveBeenCalled();
  });

  it("stops guessing after too many attempts", async () => {
    vi.mocked(rateLimit).mockResolvedValueOnce({ allowed: false, retryAfterSeconds: 600 });

    const result = await lookupBooking({ status: "idle" }, form("YUFHBD", "Perera"));

    expect(result).toMatchObject({ status: "error", message: "Too many attempts. Please try again in 10 minutes." });
    expect(openManageSession).not.toHaveBeenCalled();
  });
});

describe("closeSession", () => {
  it("drops the token and returns to the lookup form", async () => {
    await expect(closeSession()).rejects.toThrow("NEXT_REDIRECT:/manage");

    expect(clearSession).toHaveBeenCalled();
  });
});

describe("cancelBooking", () => {
  it("cancels with the token from the cookie", async () => {
    vi.mocked(readSession).mockResolvedValue(SESSION);
    vi.mocked(cancelPnr).mockResolvedValue({ kind: "ok", pnr: PNR });

    expect(await cancelBooking()).toEqual({ kind: "ok", pnr: PNR });
    expect(cancelPnr).toHaveBeenCalledWith("YUFHBD", SESSION.token);
  });

  it("does nothing without a session", async () => {
    vi.mocked(readSession).mockResolvedValue(null);

    expect(await cancelBooking()).toEqual({ kind: "unauthorized" });
    expect(cancelPnr).not.toHaveBeenCalled();
  });

  it("drops the cookie when the API says the token is no longer good", async () => {
    vi.mocked(readSession).mockResolvedValue(SESSION);
    vi.mocked(cancelPnr).mockResolvedValue({ kind: "unauthorized" });

    expect(await cancelBooking()).toEqual({ kind: "unauthorized" });
    expect(clearSession).toHaveBeenCalled();
  });

  it("is rate limited", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 20 });

    expect(await cancelBooking()).toEqual({ kind: "rate-limited", retryAfterSeconds: 20 });
    expect(cancelPnr).not.toHaveBeenCalled();
  });
});

describe("changeSeat", () => {
  beforeEach(() => vi.mocked(readSession).mockResolvedValue(SESSION));

  it("sends a valid change with the token", async () => {
    vi.mocked(changeSeatCall).mockResolvedValue({ kind: "ok", pnr: PNR });

    await changeSeat({ passengerIndex: 0, segmentIndex: 1, seatNumber: "30C" });

    expect(changeSeatCall).toHaveBeenCalledWith("YUFHBD", SESSION.token, {
      passengerIndex: 0, segmentIndex: 1, seatNumber: "30C",
    });
  });

  it.each([
    [{ passengerIndex: -1, segmentIndex: 0, seatNumber: "30C" }],
    [{ passengerIndex: 0, segmentIndex: 9, seatNumber: "30C" }],
    [{ passengerIndex: 1.5, segmentIndex: 0, seatNumber: "30C" }],
    [{ passengerIndex: 0, segmentIndex: 0, seatNumber: "<script>" }],
    [{ passengerIndex: 0, segmentIndex: 0, seatNumber: "30Z" }],
  ])("rejects %j without calling the API", async (change) => {
    expect((await changeSeat(change)).kind).toBe("rejected");
    expect(changeSeatCall).not.toHaveBeenCalled();
  });
});

describe("checkInFlight", () => {
  const PASSES = [
    {
      passengerName: "PERERA/PRIYA", locator: "YUFHBD", flightNumber: "SK501", departureDate: "2026-11-02",
      seatNumber: "11D", sequence: 7, barcode: "signed",
    },
  ];

  beforeEach(() => vi.mocked(readSession).mockResolvedValue(SESSION));

  it("checks in the flight with the booking's own token", async () => {
    vi.mocked(checkInCall).mockResolvedValue({ kind: "ok", passes: PASSES, created: true });

    expect(await checkInFlight(0)).toEqual({ kind: "ok", passes: PASSES, created: true });
    expect(checkInCall).toHaveBeenCalledWith("YUFHBD", SESSION.token, 0);
  });

  it.each([[-1], [4], [1.5], [Number.NaN]])("rejects flight index %s without calling the API", async (index) => {
    expect((await checkInFlight(index)).kind).toBe("rejected");
    expect(checkInCall).not.toHaveBeenCalled();
  });

  it("needs a session", async () => {
    vi.mocked(readSession).mockResolvedValue(null);

    expect(await checkInFlight(0)).toEqual({ kind: "unauthorized" });
    expect(checkInCall).not.toHaveBeenCalled();
  });

  it("passes the closed-window message through and drops a dead token", async () => {
    vi.mocked(checkInCall).mockResolvedValueOnce({ kind: "closed", detail: "Check-in opens at 2026-11-01T10:00:00Z" });
    expect(await checkInFlight(0)).toEqual({ kind: "closed", detail: "Check-in opens at 2026-11-01T10:00:00Z" });
    expect(clearSession).not.toHaveBeenCalled();

    vi.mocked(checkInCall).mockResolvedValueOnce({ kind: "unauthorized" });
    await checkInFlight(0);
    expect(clearSession).toHaveBeenCalled();
  });

  it("is rate limited", async () => {
    vi.mocked(rateLimit).mockResolvedValue({ allowed: false, retryAfterSeconds: 9 });

    expect(await checkInFlight(0)).toEqual({ kind: "rate-limited", retryAfterSeconds: 9 });
    expect(checkInCall).not.toHaveBeenCalled();
  });
});
