import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SeatMap as SeatMapData, Segment } from "@/lib/api/booking";
import { BookingWizard } from "./booking-wizard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/app/book/actions", () => ({ holdSeats: vi.fn(), createBooking: vi.fn(), quoteTrip: vi.fn(), releaseSeats: vi.fn() }));

const OUTBOUND: Segment = { flightNumber: "SK001", departureDate: "2026-11-11" };
const ONWARD: Segment = { flightNumber: "SK002", departureDate: "2026-11-12" };

function mapFor(segment: Segment): SeatMapData {
  return {
    flightNumber: segment.flightNumber,
    departureDate: segment.departureDate,
    seats: [
      { seatNumber: "11A", row: 11, letter: "A", cabin: "ECONOMY", status: "AVAILABLE" },
      { seatNumber: "11B", row: 11, letter: "B", cabin: "ECONOMY", status: "AVAILABLE" },
      { seatNumber: "11C", row: 11, letter: "C", cabin: "ECONOMY", status: "AVAILABLE" },
    ],
  } as SeatMapData;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("BookingWizard", () => {
  it("offers a seat row for each passenger and moves on to the next passenger after a seat is chosen", () => {
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={2} />);

    expect(screen.getByRole("button", { name: /Passenger 1/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Passenger 2/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

    expect(screen.getByRole("button", { name: /Passenger 2/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeDisabled(); // Passenger 2 still has no seat
  });

  // The booking page is the same page for every search: a link, Back or Forward to a booking for a different number of passengers
  // re-renders it with new props, and what the traveller had chosen for the old booking must not be carried over.
  it("starts afresh when the same page is shown for a different number of passengers", () => {
    const { rerender } = render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

    rerender(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={2} />);

    expect(screen.getByRole("button", { name: /Passenger 2/ })).toBeInTheDocument();
    expect(() => fireEvent.click(screen.getByRole("button", { name: /^Seat 11B/ }))).not.toThrow();   // this is the crash that was reported
    expect(screen.getByRole("button", { name: /Passenger 2/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeDisabled();
  });

  it("does not keep a seat chosen for the earlier booking", () => {
    const { rerender } = render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));
    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeEnabled();

    rerender(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={2} />);

    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeDisabled(); // nobody has a seat in the new booking
  });

  it("starts afresh when the same page is shown for different flights", () => {
    const { rerender } = render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

    rerender(<BookingWizard segments={[OUTBOUND, ONWARD]} seatMaps={[mapFor(OUTBOUND), mapFor(ONWARD)]} passengerCount={1} />);

    expect(screen.getByRole("tab", { name: "SK002" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeDisabled();
  });

  it("keeps what the traveller has chosen when the page re-renders with the same booking", () => {
    const { rerender } = render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

    rerender(<BookingWizard segments={[{ ...OUTBOUND }]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);   // new objects, same booking

    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeEnabled();
  });
});
