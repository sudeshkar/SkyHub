import { beforeEach, describe, expect, it, vi } from "vitest";
import { createHold, createPnr, releaseHold } from "@/lib/api/booking";
import { rateLimit } from "@/lib/rate-limit";
import { createBooking, holdSeats, releaseSeats, type BookingInput } from "./actions";

vi.mock("@/lib/rate-limit", () => ({ POLICIES: {}, rateLimit: vi.fn(async () => ({ allowed: true })) }));
vi.mock("@/lib/api/booking", () => ({ createPnr: vi.fn(), createHold: vi.fn(), releaseHold: vi.fn() }));

const KEY = "0f8fad5b-d9cb-469f-a165-70867728950e";
const HOLD = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const valid: BookingInput = {
  contactEmail: " priya@example.com ",
  segments: [{ flightNumber: "SK501", departureDate: "2026-11-02" }],
  passengers: [{ firstName: " Priya ", lastName: "O'Neil", seats: ["11D"] }],
};

describe("createBooking", () => {
  beforeEach(() => {
    vi.mocked(createPnr).mockReset();
    vi.mocked(createPnr).mockResolvedValue({ kind: "unavailable" });
  });

  it("forwards trimmed input with the idempotency key", async () => {
    await createBooking(valid, KEY);

    expect(createPnr).toHaveBeenCalledWith(
      {
        contactEmail: "priya@example.com",
        segments: valid.segments,
        passengers: [{ firstName: "Priya", lastName: "O'Neil", seats: ["11D"] }],
      },
      KEY,
    );
  });

  it.each<[string, BookingInput, string]>([
    ["a malformed email", { ...valid, contactEmail: "nope" }, KEY],
    ["a script in a name", { ...valid, passengers: [{ firstName: "<script>", lastName: "X", seats: ["11D"] }] }, KEY],
    ["a missing seat", { ...valid, passengers: [{ firstName: "Priya", lastName: "Perera", seats: [] }] }, KEY],
    ["a forged seat", { ...valid, passengers: [{ firstName: "Priya", lastName: "Perera", seats: ["11Z"] }] }, KEY],
    ["a bad idempotency key", valid, "not-a-uuid"],
  ])("rejects %s without calling the API", async (_case, input, key) => {
    const outcome = await createBooking(input, key);

    expect(outcome.kind).toBe("rejected");
    expect(createPnr).not.toHaveBeenCalled();
  });

  it("refuses a rate-limited client without calling the API", async () => {
    vi.mocked(rateLimit).mockResolvedValueOnce({ allowed: false, retryAfterSeconds: 30 });

    expect(await createBooking(valid, KEY)).toEqual({ kind: "rate-limited", retryAfterSeconds: 30 });
    expect(createPnr).not.toHaveBeenCalled();
  });

  it("sends the hold id so the held seats become the booking", async () => {
    await createBooking({ ...valid, holdId: HOLD }, KEY);

    expect(createPnr).toHaveBeenCalledWith(expect.objectContaining({ holdId: HOLD }), KEY);
  });

  it("rejects a malformed hold id without calling the API", async () => {
    expect((await createBooking({ ...valid, holdId: "nope" }, KEY)).kind).toBe("rejected");
    expect(createPnr).not.toHaveBeenCalled();
  });
});

describe("holdSeats", () => {
  const segments = [
    { flightNumber: "SK501", departureDate: "2026-11-02" },
    { flightNumber: "SK901", departureDate: "2026-11-02" },
  ];

  beforeEach(() => {
    vi.mocked(createHold).mockReset();
    vi.mocked(createHold).mockResolvedValue({ kind: "held", holdId: HOLD, expiresAt: "2026-10-29T10:10:00Z" });
  });

  it("holds one seat per passenger per flight", async () => {
    const outcome = await holdSeats(segments, [
      ["11D", "24A"],
      ["11E", "24B"],
    ]);

    expect(outcome).toEqual({ kind: "held", holdId: HOLD, expiresAt: "2026-10-29T10:10:00Z" });
    expect(createHold).toHaveBeenCalledWith([
      { flightNumber: "SK501", departureDate: "2026-11-02", seatNumber: "11D" },
      { flightNumber: "SK501", departureDate: "2026-11-02", seatNumber: "11E" },
      { flightNumber: "SK901", departureDate: "2026-11-02", seatNumber: "24A" },
      { flightNumber: "SK901", departureDate: "2026-11-02", seatNumber: "24B" },
    ]);
  });

  it.each<[string, string[][]]>([
    ["a forged seat", [["11Z", "24A"]]],
    ["a missing seat", [["11D"]]],
    ["no passengers", []],
  ])("rejects %s without calling the API", async (_case, seats) => {
    expect((await holdSeats(segments, seats)).kind).toBe("rejected");
    expect(createHold).not.toHaveBeenCalled();
  });

  it("rejects a forged flight or date", async () => {
    expect((await holdSeats([{ flightNumber: "../x", departureDate: "2026-11-02" }], [["11D"]])).kind).toBe("rejected");
    expect((await holdSeats([{ flightNumber: "SK501", departureDate: "tomorrow" }], [["11D"]])).kind).toBe("rejected");
    expect(createHold).not.toHaveBeenCalled();
  });

  it("refuses a rate-limited client without calling the API", async () => {
    vi.mocked(rateLimit).mockResolvedValueOnce({ allowed: false, retryAfterSeconds: 20 });

    expect(await holdSeats(segments, [["11D", "24A"]])).toEqual({ kind: "rate-limited", retryAfterSeconds: 20 });
    expect(createHold).not.toHaveBeenCalled();
  });

  it("passes a seat conflict through", async () => {
    vi.mocked(createHold).mockResolvedValueOnce({ kind: "seat-taken", detail: "Seat 11D is no longer available" });

    expect((await holdSeats(segments, [["11D", "24A"]])).kind).toBe("seat-taken");
  });
});

describe("releaseSeats", () => {
  it("releases a well-formed hold id and ignores anything else", async () => {
    await releaseSeats(HOLD);
    await releaseSeats("../../etc/passwd");

    expect(releaseHold).toHaveBeenCalledTimes(1);
    expect(releaseHold).toHaveBeenCalledWith(HOLD);
  });
});
