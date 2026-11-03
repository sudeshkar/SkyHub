import { fireEvent, render, screen, within } from "@testing-library/react";
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

describe("BookingWizard saved passengers (ADR-0078)", () => {
  const SAVED = [{ firstName: "Priya", lastName: "Perera" }, { firstName: "Raj", lastName: "Perera" }];

  async function atPassengers(loadSaved: () => Promise<{ firstName: string; lastName: string }[]>, count = 2) {
    const { holdSeats } = await import("@/app/book/actions");
    vi.mocked(holdSeats).mockResolvedValue({ kind: "held", holdId: "h1", expiresAt: "2099-01-01T00:00:00Z" } as never);
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={count} loadSaved={loadSaved} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));
    if (count > 1) fireEvent.click(screen.getByRole("button", { name: /^Seat 11B/ }));
    fireEvent.click(screen.getByRole("button", { name: "Continue to passengers" }));
    await screen.findByRole("group", { name: "Passenger 1" });
  }

  it("asks for the saved names only when the traveller reaches the passengers step", async () => {
    const loadSaved = vi.fn().mockResolvedValue(SAVED);
    const { holdSeats } = await import("@/app/book/actions");
    vi.mocked(holdSeats).mockResolvedValue({ kind: "held", holdId: "h1", expiresAt: "2099-01-01T00:00:00Z" } as never);
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} loadSaved={loadSaved} />);

    expect(loadSaved).not.toHaveBeenCalled();   // choosing seats needs no account

    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));
    fireEvent.click(screen.getByRole("button", { name: "Continue to passengers" }));
    await screen.findByRole("group", { name: "Passenger 1" });

    expect(loadSaved).toHaveBeenCalledTimes(1);
  });

  it("fills one passenger's names from a saved name and leaves the others alone", async () => {
    await atPassengers(vi.fn().mockResolvedValue(SAVED));

    const picker = await screen.findByLabelText("Use a saved passenger for passenger 2");
    fireEvent.change(picker, { target: { value: "1" } });   // Raj Perera

    const second = screen.getByRole("group", { name: "Passenger 2" });
    expect(within(second).getByLabelText("First name")).toHaveValue("Raj");
    expect(within(second).getByLabelText("Last name")).toHaveValue("Perera");
    const first = screen.getByRole("group", { name: "Passenger 1" });
    expect(within(first).getByLabelText("First name")).toHaveValue("");
  });

  it("lets the traveller change a name that was filled in", async () => {
    await atPassengers(vi.fn().mockResolvedValue(SAVED), 1);
    fireEvent.change(await screen.findByLabelText("Use a saved passenger for passenger 1"), { target: { value: "0" } });

    fireEvent.change(screen.getByLabelText("First name"), { target: { value: "Priyanka" } });

    expect(screen.getByLabelText("First name")).toHaveValue("Priyanka");
  });

  it.each([
    ["none are saved", () => vi.fn().mockResolvedValue([])],
    ["the names cannot be loaded", () => vi.fn().mockRejectedValue(new Error("offline"))],
  ])("shows no picker, and nothing is missing from the form, when %s", async (_why, make) => {
    await atPassengers(make(), 1);

    expect(screen.queryByLabelText(/Use a saved passenger/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("First name")).toBeRequired();
  });
});
