import { afterEach, describe, expect, it, vi } from "vitest";
import { forgetRecentBooking, readRecentBooking, rememberBooking, tripKey, type RecentBooking } from "./recent-booking";

const BOOKING: RecentBooking = {
  locator: "ST3X9Q",
  contactEmail: "t****@example.com",
  paid: "$850.00",
  trip: "SK501_2026-11-14,SK001_2026-11-14",
  passengers: [{ firstName: "Test", lastName: "Traveller", seats: ["1F", "2F"] }],
};

afterEach(() => {
  vi.restoreAllMocks();
  forgetRecentBooking();
});

describe("recent booking (this tab only)", () => {
  it("names a trip by its flights and dates, in booking order", () => {
    expect(
      tripKey([
        { flightNumber: "SK501", departureDate: "2026-11-14" },
        { flightNumber: "SK001", departureDate: "2026-11-14" },
      ]),
    ).toBe("SK501_2026-11-14,SK001_2026-11-14");
  });

  it("remembers the booking just made, to show it again", () => {
    rememberBooking(BOOKING);

    expect(readRecentBooking()).toEqual(BOOKING);
  });

  it("keeps it in this tab's session storage, which the browser clears when the tab closes", () => {
    rememberBooking(BOOKING);

    expect(window.sessionStorage.length).toBe(1);
    expect(window.localStorage.length).toBe(0);
  });

  it("has nothing to show when nothing was booked in this tab", () => {
    expect(readRecentBooking()).toBeNull();
  });

  it("ignores something stored that is not a booking", () => {
    window.sessionStorage.setItem("skyhub.recent-booking", "{not json");
    expect(readRecentBooking()).toBeNull();

    window.sessionStorage.setItem("skyhub.recent-booking", JSON.stringify({ locator: 7 }));
    expect(readRecentBooking()).toBeNull();
  });

  it("carries on when the browser refuses storage (a private window, or storage turned off)", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });

    expect(() => rememberBooking(BOOKING)).not.toThrow();
    expect(readRecentBooking()).toBeNull();
  });
});
