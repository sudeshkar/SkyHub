import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SeatMap as SeatMapData, Segment } from "@/lib/api/booking";
import { forgetRecentBooking, readRecentBooking, rememberBooking } from "@/lib/recent-booking";
import { BookingWizard } from "./booking-wizard";

const router = vi.hoisted(() => ({ refresh: vi.fn(), back: vi.fn(), push: vi.fn(), replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));
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

  it("goes back to the flight results from the seats step", () => {
    vi.spyOn(window.history, "length", "get").mockReturnValue(3);
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);

    fireEvent.click(screen.getByRole("button", { name: "Back to flight results" }));

    expect(router.back).toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it("goes to the start when the booking was opened directly, with nothing to go back to", () => {
    vi.spyOn(window.history, "length", "get").mockReturnValue(1);
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);

    fireEvent.click(screen.getByRole("button", { name: "Back to flight results" }));

    expect(router.back).not.toHaveBeenCalled();
    expect(router.push).toHaveBeenCalledWith("/");
    vi.restoreAllMocks();
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

  describe("a booking of more than one flight", () => {
    const twoFlights = () =>
      render(<BookingWizard segments={[OUTBOUND, ONWARD]} seatMaps={[mapFor(OUTBOUND), mapFor(ONWARD)]} passengerCount={1} />);

    it("moves on to the next flight once everyone has a seat on this one, and says so", () => {
      twoFlights();

      fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

      expect(screen.getByRole("tab", { name: "SK002" })).toHaveAttribute("aria-selected", "true");
      expect(screen.getByRole("status")).toHaveTextContent("Seat 11A chosen on SK001. Now choose a seat on SK002.");
    });

    it("marks a flight whose seats are all chosen", () => {
      twoFlights();

      fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

      expect(screen.getByRole("tab", { name: "SK001" })).toHaveAttribute("data-complete", "true");
      expect(screen.getByRole("tab", { name: "SK002" })).toHaveAttribute("data-complete", "false");
    });

    it("offers the flight still without seats as the next step, instead of a button that does nothing", () => {
      twoFlights();
      fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ })); // moves on to SK002
      fireEvent.click(screen.getByRole("tab", { name: "SK001" })); // the traveller goes back to look

      fireEvent.click(screen.getByRole("button", { name: "Next flight: SK002" }));

      expect(screen.getByRole("tab", { name: "SK002" })).toHaveAttribute("aria-selected", "true");
    });

    it("lets the traveller continue once every flight has seats", () => {
      twoFlights();

      fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));
      fireEvent.click(screen.getByRole("button", { name: /^Seat 11B/ })); // on SK002

      expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeEnabled();
      expect(screen.getByRole("status")).toHaveTextContent("Every seat is chosen");
    });
  });

  it("keeps what the traveller has chosen when the page re-renders with the same booking", () => {
    const { rerender } = render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);
    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));

    rerender(<BookingWizard segments={[{ ...OUTBOUND }]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);   // new objects, same booking

    expect(screen.getByRole("button", { name: "Continue to passengers" })).toBeEnabled();
  });
});

describe("BookingWizard after paying", () => {
  afterEach(() => forgetRecentBooking());

  it("remembers the booking in this tab and swaps the booking page for the confirmation", async () => {
    const actions = await import("@/app/book/actions");
    vi.mocked(actions.holdSeats).mockResolvedValue({ kind: "held", holdId: "h1", expiresAt: "2099-01-01T00:00:00Z" } as never);
    vi.mocked(actions.quoteTrip).mockResolvedValue({ kind: "quoted", amount: 850, currency: "USD" } as never);
    vi.mocked(actions.createBooking).mockResolvedValue({
      kind: "booked",
      pnr: {
        locator: "ST3X9Q",
        status: "CONFIRMED",
        contactEmail: "t****@example.com",
        segments: [OUTBOUND],
        passengers: [{ firstName: "Test", lastName: "Traveller", seats: ["11A"] }],
        createdAt: "2026-11-10T10:00:00Z",
      },
    } as never);
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} loadSaved={async () => []} />);

    fireEvent.click(screen.getByRole("button", { name: /^Seat 11A/ }));
    fireEvent.click(screen.getByRole("button", { name: "Continue to passengers" }));
    fireEvent.change(await screen.findByLabelText("First name"), { target: { value: "Test" } });
    fireEvent.change(screen.getByLabelText("Last name"), { target: { value: "Traveller" } });
    fireEvent.change(screen.getByLabelText(/Contact email/), { target: { value: "test@example.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Review booking" }));
    fireEvent.click(await screen.findByRole("button", { name: "Pay $850.00 and book" }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith("/book/confirmed"));
    expect(readRecentBooking()).toEqual({
      locator: "ST3X9Q",
      contactEmail: "t****@example.com",
      paid: "$850.00",
      trip: "SK001_2026-11-11",
      passengers: [{ firstName: "Test", lastName: "Traveller", seats: ["11A"] }],
    });
  });
});

describe("BookingWizard booking the same flights again", () => {
  afterEach(() => forgetRecentBooking());
  const remember = (trip: string) =>
    rememberBooking({ locator: "ST3X9Q", contactEmail: "t****@example.com", paid: "$850.00", trip, passengers: [] });

  it("warns, without blocking, when these flights were booked in this tab a moment ago", () => {
    remember("SK001_2026-11-11");
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);

    // A pop-up over the page, so the warning takes no room from the seat map.
    const warning = screen.getByRole("alertdialog", { name: "You have just booked these flights" });
    expect(warning).toHaveTextContent("You booked these flights a moment ago (ST3X9Q).");
    expect(within(warning).getByRole("link", { name: "See that booking" })).toHaveAttribute("href", "/book/confirmed");

    fireEvent.click(within(warning).getByRole("button", { name: "Book again" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Seat 11A/ })).toBeEnabled(); // booking again is allowed
  });

  it("lets the warning be closed with the Escape key, to book again", () => {
    remember("SK001_2026-11-11");
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);

    fireEvent.keyDown(document, { key: "Escape" });

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("says nothing for other flights", () => {
    remember("SK002_2026-11-12");
    render(<BookingWizard segments={[OUTBOUND]} seatMaps={[mapFor(OUTBOUND)]} passengerCount={1} />);

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
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
