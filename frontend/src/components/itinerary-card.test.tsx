import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Itinerary } from "@/lib/api/search";
import { ItineraryCard } from "./itinerary-card";
import { segmentsOf } from "./journey-bar";

// Same shape and values the API returns for CMB → JFK on 2026-11-02.
const viaTransfer: Itinerary = {
  legs: [
    { flightNumber: "SK501", origin: "CMB", destination: "DXB", departureTime: "2026-11-02T02:00:00Z", arrivalTime: "2026-11-02T06:50:00Z", aircraftType: "A350", fare: 620, currency: "USD" },
    { flightNumber: "SK901", origin: "DWC", destination: "JFK", departureTime: "2026-11-02T10:30:00Z", arrivalTime: "2026-11-03T00:30:00Z", aircraftType: "B777", fare: 620, currency: "USD" },
  ],
  connections: [
    { arrivalAirport: "DXB", departureAirport: "DWC", layoverMinutes: 220, requiresAirportTransfer: true, transferMinutes: 120 },
  ],
  stops: 1,
  totalDurationMinutes: 1350,
  fareFrom: 1240,
  currency: "USD",
};

const direct: Itinerary = {
  legs: [
    { flightNumber: "SK001", origin: "DXB", destination: "LHR", departureTime: "2026-11-02T08:30:00Z", arrivalTime: "2026-11-02T16:20:00Z", aircraftType: "A380", fare: 540, currency: "USD" },
  ],
  connections: [],
  stops: 0,
  totalDurationMinutes: 470,
  fareFrom: 540,
  currency: "USD",
};

describe("ItineraryCard", () => {
  it("shows local departure and arrival times", () => {
    render(<ItineraryCard itinerary={viaTransfer} />);

    expect(screen.getByText("07:30")).toBeInTheDocument();
    expect(screen.getByText("19:30")).toBeInTheDocument();
  });

  it("summarises stops, hub and duration", () => {
    render(<ItineraryCard itinerary={viaTransfer} />);

    expect(screen.getByText("1 stop via DXB · 22h 30m")).toBeInTheDocument();
  });

  it("warns about a change of airport with the transfer allowance", () => {
    render(<ItineraryCard itinerary={viaTransfer} />);

    const note = screen.getByRole("note");
    expect(note).toHaveTextContent("Change of airport in Dubai");
    expect(note).toHaveTextContent("depart from Al Maktoum International (DWC)");
    expect(note).toHaveTextContent("3h 40m connection allows 2h");
  });

  it("does not warn when there is no change of airport", () => {
    render(<ItineraryCard itinerary={direct} />);

    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.getByText("Direct · 7h 50m")).toBeInTheDocument();
  });

  it("shows the price per person", () => {
    render(<ItineraryCard itinerary={viaTransfer} />);

    expect(screen.getByText("$1,240")).toBeInTheDocument();
  });
});

describe("segmentsOf", () => {
  it("splits a transfer connection into road time and waiting time", () => {
    expect(segmentsOf(viaTransfer)).toEqual([
      { kind: "flight", minutes: 290 },
      { kind: "transfer", minutes: 120 },
      { kind: "wait", minutes: 100 },
      { kind: "flight", minutes: 840 },
    ]);
  });
});
