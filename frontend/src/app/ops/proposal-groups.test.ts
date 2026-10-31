import { describe, expect, it } from "vitest";
import type { Proposal } from "@/lib/api/agent";
import { groupProposals } from "./proposal-groups";

const rebook = (locator: string, flightNumber: string, departureDate: string, passengerCount = 1): Proposal => ({
  locator, action: "REBOOK", flightNumber, departureDate, segmentIndex: 0, passengerCount, rationale: "Earliest flight that fits.",
});
const human = (locator: string, passengerCount = 3): Proposal => ({
  locator, action: "NEEDS_HUMAN", flightNumber: null, departureDate: null, segmentIndex: null, passengerCount, rationale: "no seats",
});

describe("groupProposals", () => {
  it("puts bookings going to the same flight and date together, largest group first", () => {
    const groups = groupProposals([
      rebook("AAAAAA", "SK505", "2026-11-02"),
      rebook("BBBBBB", "SK503", "2026-11-02", 2),
      rebook("CCCCCC", "SK503", "2026-11-02", 3),
    ], "");

    expect(groups.map((g) => [g.flightNumber, g.departureDate, g.proposals.map((p) => p.locator)])).toEqual([
      ["SK503", "2026-11-02", ["BBBBBB", "CCCCCC"]],
      ["SK505", "2026-11-02", ["AAAAAA"]],
    ]);
    expect(groups[0].passengers).toBe(5);
    expect(groups[0].kind).toBe("rebook");
  });

  it("keeps the same flight on different dates apart", () => {
    const groups = groupProposals([rebook("AAAAAA", "SK503", "2026-11-02"), rebook("BBBBBB", "SK503", "2026-11-03")], "");

    expect(groups).toHaveLength(2);
  });

  it("orders groups of the same size by flight and date so the page does not shuffle", () => {
    const groups = groupProposals([rebook("AAAAAA", "SK510", "2026-11-02"), rebook("BBBBBB", "SK503", "2026-11-03"),
      rebook("CCCCCC", "SK503", "2026-11-02")], "");

    expect(groups.map((g) => `${g.flightNumber}@${g.departureDate}`)).toEqual(["SK503@2026-11-02", "SK503@2026-11-03", "SK510@2026-11-02"]);
  });

  it("keeps bookings that need a person in a last group of their own, whatever its size", () => {
    const groups = groupProposals([human("DDDDDD"), human("EEEEEE"), human("FFFFFF"), rebook("AAAAAA", "SK503", "2026-11-02")], "");

    expect(groups.map((g) => g.kind)).toEqual(["rebook", "person"]);
    expect(groups[1].proposals.map((p) => p.locator)).toEqual(["DDDDDD", "EEEEEE", "FFFFFF"]);
    expect(groups[1].passengers).toBe(9);
    expect(groups[1].flightNumber).toBeUndefined();
  });

  it("keeps each booking's place within its group", () => {
    const groups = groupProposals([rebook("CCCCCC", "SK503", "2026-11-02"), rebook("AAAAAA", "SK503", "2026-11-02"),
      rebook("BBBBBB", "SK503", "2026-11-02")], "");

    expect(groups[0].proposals.map((p) => p.locator)).toEqual(["CCCCCC", "AAAAAA", "BBBBBB"]);
  });

  it("finds a booking by part of its reference, ignoring case and spaces, and drops groups with nothing left", () => {
    const all = [rebook("ABC234", "SK503", "2026-11-02"), rebook("ABD999", "SK503", "2026-11-02"), rebook("XYZ777", "SK505", "2026-11-02"),
      human("ABC111")];

    expect(groupProposals(all, " abc ").flatMap((g) => g.proposals.map((p) => p.locator))).toEqual(["ABC234", "ABC111"]);
    expect(groupProposals(all, "xyz").map((g) => g.flightNumber)).toEqual(["SK505"]);
    expect(groupProposals(all, "zzz")).toEqual([]);
  });

  it("counts passengers from the bookings that are shown, not the ones that were hidden", () => {
    const groups = groupProposals([rebook("ABC234", "SK503", "2026-11-02", 2), rebook("XYZ777", "SK503", "2026-11-02", 5)], "abc");

    expect(groups[0].passengers).toBe(2);
  });

  it("treats a missing passenger count as no passengers", () => {
    const missing = { ...rebook("ABC234", "SK503", "2026-11-02"), passengerCount: null };

    expect(groupProposals([missing], "")[0].passengers).toBe(0);
  });

  it("returns nothing for nothing, in one pass however many there are", () => {
    expect(groupProposals([], "")).toEqual([]);
    const many = Array.from({ length: 5_000 }, (_, i) => rebook(`B${String(i).padStart(5, "0")}`, `SK${500 + (i % 7)}`, "2026-11-02"));

    const started = performance.now();
    const groups = groupProposals(many, "");

    expect(groups).toHaveLength(7);
    expect(groups.reduce((n, g) => n + g.proposals.length, 0)).toBe(5_000);
    expect(performance.now() - started).toBeLessThan(500);   // linear, not quadratic: generous bound, so it is not a flaky timing test
  });
});
