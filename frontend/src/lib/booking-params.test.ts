import { describe, expect, it } from "vitest";
import { encodeSegments, parseBooking, parsePassengers } from "./booking-params";

describe("parseBooking", () => {
  it("round-trips encoded segments", () => {
    const segments = [
      { flightNumber: "SK501", departureDate: "2026-11-02" },
      { flightNumber: "SK901", departureDate: "2026-11-02" },
    ];

    expect(parseBooking({ legs: encodeSegments(segments), pax: "2" })).toEqual({
      ok: true,
      selection: { segments, passengers: 2 },
    });
  });

  it.each([[""], ["SK501"], ["SK501_02-11-2026"], ["SK501_2026-11-02,<script>_2026-11-02"]])(
    "rejects malformed legs %j",
    (legs) => {
      expect(parseBooking({ legs })).toEqual({ ok: false });
    },
  );

  it("rejects more than four segments", () => {
    const legs = Array.from({ length: 5 }, (_, i) => `SK50${i}_2026-11-02`).join(",");
    expect(parseBooking({ legs })).toEqual({ ok: false });
  });
});

describe("parsePassengers", () => {
  it.each([
    [undefined, 1],
    ["3", 3],
    ["9", 9],
    ["10", 1],
    ["0", 1],
    ["two", 1],
  ])("parses %j as %i", (value, expected) => {
    expect(parsePassengers(value)).toBe(expected);
  });
});
