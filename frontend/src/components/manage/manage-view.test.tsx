import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { cancelBooking, changeSeat, loadSeatMap } from "@/app/manage/actions";
import type { Pnr, SeatMap } from "@/lib/api/booking";
import { ManageView } from "./manage-view";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
vi.mock("@/app/manage/actions", () => ({
  cancelBooking: vi.fn(),
  changeSeat: vi.fn(),
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
