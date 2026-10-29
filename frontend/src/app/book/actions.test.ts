import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPnr } from "@/lib/api/booking";
import { createBooking, type BookingInput } from "./actions";

vi.mock("@/lib/api/booking", () => ({ createPnr: vi.fn() }));

const KEY = "0f8fad5b-d9cb-469f-a165-70867728950e";
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
});
