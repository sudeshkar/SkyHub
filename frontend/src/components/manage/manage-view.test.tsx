import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cancelBooking, changeSeat, checkInFlight, loadSeatMap } from "@/app/manage/actions";
import type { Pnr, SeatMap } from "@/lib/api/booking";
import { ManageView } from "./manage-view";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("@/app/manage/actions", () => ({
  cancelBooking: vi.fn(),
  changeSeat: vi.fn(),
  checkInFlight: vi.fn(),
  closeSession: vi.fn(),
  loadSeatMap: vi.fn(),
}));

const PNR: Pnr = {
  locator: "YUFHBD",
  status: "CONFIRMED",
  contactEmail: "p****@example.com",
  segments: [
    { flightNumber: "SK501", departureDate: "2026-11-02" },
    { flightNumber: "SK901", departureDate: "2026-11-02" },
  ],
  passengers: [
    { firstName: "Priya", lastName: "Perera", seats: ["11D", "24A"] },
    { firstName: "Raj", lastName: "Perera", seats: ["11E", "24B"] },
  ],
  createdAt: "2026-10-29T10:00:00Z",
};

const MAP: SeatMap = {
  flightNumber: "SK501",
  departureDate: "2026-11-02",
  seats: [
    { seatNumber: "11D", row: 11, letter: "D", cabin: "ECONOMY", status: "BOOKED" },
    { seatNumber: "11E", row: 11, letter: "E", cabin: "ECONOMY", status: "BOOKED" },
    { seatNumber: "12A", row: 12, letter: "A", cabin: "ECONOMY", status: "AVAILABLE" },
  ],
};

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(loadSeatMap).mockResolvedValue(MAP);
});

describe("ManageView", () => {
  it("shows the booking: reference, status, and each passenger's seat on each flight", () => {
    render(<ManageView pnr={PNR} />);

    expect(screen.getByLabelText("Booking reference Y U F H B D")).toBeInTheDocument();
    expect(screen.getByText("Confirmed")).toBeInTheDocument();
    expect(screen.getByText("Seat 11D")).toBeInTheDocument();
    expect(screen.getByText("Seat 24B")).toBeInTheDocument();
    expect(screen.getByText("Confirmation sent to p****@example.com")).toBeInTheDocument();
  });

  describe("cancelling", () => {
    it("asks first, then cancels and refreshes", async () => {
      vi.mocked(cancelBooking).mockResolvedValue({ kind: "ok", pnr: { ...PNR, status: "CANCELLED" } });
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Cancel booking" }));
      expect(cancelBooking).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole("button", { name: "Yes, cancel booking" }));

      expect(await screen.findByRole("status")).toHaveTextContent("Your booking is cancelled and your seats are released.");
      expect(cancelBooking).toHaveBeenCalledTimes(1);
      expect(refresh).toHaveBeenCalled();
    });

    it("can be abandoned without cancelling anything", () => {
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Cancel booking" }));
      fireEvent.click(screen.getByRole("button", { name: "Keep booking" }));

      expect(cancelBooking).not.toHaveBeenCalled();
      expect(screen.getByRole("button", { name: "Cancel booking" })).toBeInTheDocument();
    });

    it("offers no changes on a cancelled booking", () => {
      render(<ManageView pnr={{ ...PNR, status: "CANCELLED" }} />);

      expect(screen.getByText("Cancelled")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Cancel booking" })).not.toBeInTheDocument();
      for (const button of screen.getAllByRole("button", { name: /Change seat for/ })) expect(button).toBeDisabled();
    });
  });

  describe("the money after cancelling (ADR-0016)", () => {
    const CANCELLED: Pnr = { ...PNR, status: "CANCELLED" };

    it("tells the traveller the refund rule before they cancel", () => {
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Cancel booking" }));

      expect(screen.getByRole("alertdialog")).toHaveTextContent(
        "full refund if you cancel 24 hours or more before the first flight, or if the airline cancelled a flight",
      );
    });

    it("says the money went back", () => {
      render(<ManageView pnr={{ ...CANCELLED, refund: { status: "REFUNDED", amount: 200, currency: "USD" } }} />);

      expect(screen.getByRole("region", { name: "Refund" })).toHaveTextContent("$200.00 has been refunded");
      expect(screen.queryByRole("button", { name: "Try the refund again" })).not.toBeInTheDocument();
    });

    it("explains that a late cancellation gets nothing back", () => {
      render(<ManageView pnr={{ ...CANCELLED, refund: { status: "NOT_REFUNDABLE", amount: 200, currency: "USD" } }} />);

      expect(screen.getByRole("region", { name: "Refund" })).toHaveTextContent(
        "This booking was cancelled less than 24 hours before departure, so the $200.00 you paid is not refundable.",
      );
    });

    it("shows a refund the provider has not confirmed and lets the traveller try again", async () => {
      vi.mocked(cancelBooking).mockResolvedValue({ kind: "ok", pnr: CANCELLED });
      render(<ManageView pnr={{ ...CANCELLED, refund: { status: "PENDING", amount: 200, currency: "USD" } }} />);

      expect(screen.getByRole("region", { name: "Refund" })).toHaveTextContent("Your $200.00 refund is on its way");

      fireEvent.click(screen.getByRole("button", { name: "Try the refund again" }));

      await waitFor(() => expect(cancelBooking).toHaveBeenCalledTimes(1));
      expect(refresh).toHaveBeenCalled();
    });

    it("shows no refund panel when the booking was never paid or is still active", () => {
      const { rerender } = render(<ManageView pnr={CANCELLED} />);
      expect(screen.queryByRole("region", { name: "Refund" })).not.toBeInTheDocument();

      rerender(<ManageView pnr={PNR} />);
      expect(screen.queryByRole("region", { name: "Refund" })).not.toBeInTheDocument();
    });
  });

  describe("checking in", () => {
    const PASS = {
      passengerName: "PERERA/PRIYA",
      locator: "YUFHBD",
      flightNumber: "SK501",
      departureDate: "2026-11-02",
      seatNumber: "11D",
      sequence: 7,
      barcode: "M1PERERA/PRIYA YUFHBD SK501 2026-11-02 11D 0007.sig",
    };

    it("checks in one flight and shows each passenger's boarding pass", async () => {
      vi.mocked(checkInFlight).mockResolvedValue({ kind: "ok", passes: [PASS], created: true });
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Check in for SK501" }));

      expect(await screen.findByText("PERERA/PRIYA")).toBeInTheDocument();
      expect(screen.getByText("Boarding no. 7")).toBeInTheDocument();
      expect(screen.getByText(PASS.barcode)).toBeInTheDocument();
      expect(checkInFlight).toHaveBeenCalledWith(0);
      expect(screen.queryByRole("button", { name: "Check in for SK501" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Check in for SK901" })).toBeInTheDocument(); // other flight unaffected
    });

    it("freezes the seats of a flight that is checked in", () => {
      render(<ManageView pnr={PNR} boardingPasses={{ 0: [PASS] }} />);

      expect(screen.getByText("PERERA/PRIYA")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Change seat for Priya Perera on SK501" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Change seat for Priya Perera on SK901" })).toBeEnabled();
    });

    it("explains when check-in is not open", async () => {
      vi.mocked(checkInFlight).mockResolvedValue({ kind: "closed", detail: "Check-in opens at 2026-11-01T10:00:00Z" });
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Check in for SK501" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("Check-in opens at 2026-11-01T10:00:00Z");
    });

    it("offers no check-in on a cancelled booking", () => {
      render(<ManageView pnr={{ ...PNR, status: "CANCELLED" }} />);

      expect(screen.queryByRole("button", { name: /Check in for/ })).not.toBeInTheDocument();
    });
  });

  describe("disruptions", () => {
    const DELAY = {
      segmentIndex: 1, flightNumber: "SK901", departureDate: "2026-11-02", type: "DELAYED" as const,
      revisedDeparture: "2026-11-02T14:30:00Z", reason: "Late inbound aircraft",
    };
    const CANCELLED = {
      segmentIndex: 0, flightNumber: "SK501", departureDate: "2026-11-02", type: "CANCELLED" as const,
      reason: "Aircraft grounded",
    };

    it("tells the traveller about a delay, with the new time and the reason", () => {
      render(<ManageView pnr={PNR} disruptions={[DELAY]} />);

      const notice = screen.getByRole("status", { name: "SK901 is delayed" });
      expect(notice).toHaveTextContent(/14:30 UTC/);
      expect(notice).toHaveTextContent("Late inbound aircraft");
      expect(screen.getByRole("button", { name: "Check in for SK501" })).toBeInTheDocument(); // other flight unaffected
    });

    it("says a flight is cancelled, offers a new search and takes away check-in", () => {
      render(<ManageView pnr={PNR} disruptions={[CANCELLED]} />);

      const notice = screen.getByRole("alert", { name: "SK501 is cancelled" });
      expect(notice).toHaveTextContent("Aircraft grounded");
      expect(within(notice).getByRole("link", { name: "Search for another flight" })).toHaveAttribute("href", "/");
      expect(screen.queryByRole("button", { name: "Check in for SK501" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Change seat for Priya Perera on SK501" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Check in for SK901" })).toBeInTheDocument();
    });

    it("shows nothing extra when all is well", () => {
      render(<ManageView pnr={PNR} disruptions={[]} />);

      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.queryByText(/delayed/i)).not.toBeInTheDocument();
    });
  });

  describe("changing a seat", () => {
    async function pickSeat12A() {
      fireEvent.click(screen.getByRole("button", { name: "Change seat for Priya Perera on SK501" }));
      await screen.findByRole("button", { name: "Seat 12A, economy, available" });
      fireEvent.click(screen.getByRole("button", { name: "Seat 12A, economy, available" }));
    }

    it("loads that flight's seat map, moves the passenger and refreshes", async () => {
      vi.mocked(changeSeat).mockResolvedValue({ kind: "ok", pnr: PNR });
      render(<ManageView pnr={PNR} />);

      await pickSeat12A();
      fireEvent.click(screen.getByRole("button", { name: "Move to seat 12A" }));

      expect(await screen.findByRole("status")).toHaveTextContent("Seat changed to 12A.");
      expect(loadSeatMap).toHaveBeenCalledWith({ flightNumber: "SK501", departureDate: "2026-11-02" });
      expect(changeSeat).toHaveBeenCalledWith({ passengerIndex: 0, segmentIndex: 0, seatNumber: "12A" });
      expect(refresh).toHaveBeenCalled();
    });

    it("will not move to the seat the passenger already has", async () => {
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Change seat for Priya Perera on SK501" }));
      await screen.findByRole("button", { name: "Move to seat 11D" });

      expect(screen.getByRole("button", { name: "Move to seat 11D" })).toBeDisabled();
    });

    it("keeps the other passenger's seat unavailable", async () => {
      render(<ManageView pnr={PNR} />);

      fireEvent.click(screen.getByRole("button", { name: "Change seat for Priya Perera on SK501" }));

      expect(await screen.findByRole("button", { name: "Seat 11E, economy, chosen for another passenger" })).toBeDisabled();
    });

    it("explains a seat that was taken a moment ago and reloads the map", async () => {
      vi.mocked(changeSeat).mockResolvedValue({ kind: "seat-taken", detail: "Seat 12A on SK501 2026-11-02 is no longer available" });
      render(<ManageView pnr={PNR} />);

      await pickSeat12A();
      fireEvent.click(screen.getByRole("button", { name: "Move to seat 12A" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("Seat 12A on SK501 2026-11-02 is no longer available");
      await waitFor(() => expect(loadSeatMap).toHaveBeenCalledTimes(2));
      expect(refresh).not.toHaveBeenCalled();
    });

    it("sends the person back to the lookup when the session has ended", async () => {
      vi.mocked(changeSeat).mockResolvedValue({ kind: "unauthorized" });
      render(<ManageView pnr={PNR} />);

      await pickSeat12A();
      fireEvent.click(screen.getByRole("button", { name: "Move to seat 12A" }));

      expect(await screen.findByRole("alert")).toHaveTextContent("Your session has ended");
      expect(refresh).toHaveBeenCalled();
    });
  });
});
