import { describe, expect, it } from "vitest";
import { dayOffset, formatDuration, formatMoney, formatSearchDate, localTime } from "./format";

describe("formatDuration", () => {
  it.each([
    [45, "45m"],
    [120, "2h"],
    [1350, "22h 30m"],
  ])("formats %i minutes as %s", (minutes, expected) => {
    expect(formatDuration(minutes)).toBe(expected);
  });
});

describe("localTime", () => {
  it("shows Colombo local time for a UTC departure", () => {
    expect(localTime("2026-11-02T02:00:00Z", "Asia/Colombo")).toBe("07:30");
  });

  it("applies New York winter time after daylight saving ends", () => {
    expect(localTime("2026-11-03T00:30:00Z", "America/New_York")).toBe("19:30");
  });
});

describe("dayOffset", () => {
  it("is 0 when a flight lands the same local day it left", () => {
    expect(
      dayOffset(
        { instant: "2026-11-02T02:00:00Z", timeZone: "Asia/Colombo" },
        { instant: "2026-11-03T00:30:00Z", timeZone: "America/New_York" },
      ),
    ).toBe(0);
  });

  it("is 1 when a flight lands the next local day", () => {
    expect(
      dayOffset(
        { instant: "2026-11-02T10:30:00Z", timeZone: "Asia/Dubai" },
        { instant: "2026-11-02T22:00:00Z", timeZone: "Asia/Singapore" },
      ),
    ).toBe(1);
  });
});

describe("formatSearchDate", () => {
  it("formats a date-only string without shifting the day", () => {
    expect(formatSearchDate("2026-11-02")).toBe("Mon, 2 Nov 2026");
  });
});

describe("formatMoney", () => {
  it("formats whole currency amounts", () => {
    expect(formatMoney(1240, "USD")).toBe("$1,240");
  });
});
