import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { MyBooking } from "@/lib/api/booking";
import { MyBookings } from "./my-bookings";

vi.mock("@/app/account/my-bookings-actions", () => ({ openMyBooking: vi.fn() }));

const ONE: MyBooking = {
  locator: "ABC234",
  status: "CONFIRMED",
  segments: [
    { flightNumber: "SK501", departureDate: "2026-12-01" },
    { flightNumber: "SK901", departureDate: "2026-12-01" },
  ],
  passengerCount: 2,
  createdAt: "2026-10-29T10:00:00Z",
};

describe("MyBookings", () => {
  it("shows each booking's reference, flights, passengers and an Open button", () => {
    render(<MyBookings bookings={[ONE]} truncated={false} />);

    const row = screen.getByRole("listitem");
    expect(within(row).getByText("ABC234")).toBeInTheDocument();
    expect(within(row).getByText(/SK501/)).toBeInTheDocument();
    expect(within(row).getByText(/SK901/)).toBeInTheDocument();
    expect(within(row).getByText(/2 passengers/)).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: /open booking abc234/i })).toBeInTheDocument();
  });

  it("says plainly when a booking is cancelled", () => {
    render(<MyBookings bookings={[{ ...ONE, status: "CANCELLED" }]} truncated={false} />);

    expect(screen.getByText(/cancelled/i)).toBeInTheDocument();
  });

  it("says one passenger, not 1 passengers", () => {
    render(<MyBookings bookings={[{ ...ONE, passengerCount: 1 }]} truncated={false} />);

    expect(screen.getByText(/1 passenger\b/)).toBeInTheDocument();
  });

  it("explains an empty list and how a booking gets here", () => {
    render(<MyBookings bookings={[]} truncated={false} />);

    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
    expect(screen.getByText(/no bookings/i)).toBeInTheDocument();
    expect(screen.getByText(/email address/i)).toBeInTheDocument();
  });

  it("says when older bookings are not shown", () => {
    render(<MyBookings bookings={[ONE]} truncated />);

    expect(screen.getByText(/newest 50/i)).toBeInTheDocument();
  });
});
