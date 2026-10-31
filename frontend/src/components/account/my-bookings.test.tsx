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
    render(<MyBookings bookings={[ONE]} nextCursor={null} firstPage />);

    const row = screen.getByRole("listitem");
    expect(within(row).getByText("ABC234")).toBeInTheDocument();
    expect(within(row).getByText(/SK501/)).toBeInTheDocument();
    expect(within(row).getByText(/SK901/)).toBeInTheDocument();
    expect(within(row).getByText(/2 passengers/)).toBeInTheDocument();
    expect(within(row).getByRole("button", { name: /open booking abc234/i })).toBeInTheDocument();
  });

  it("says plainly when a booking is cancelled", () => {
    render(<MyBookings bookings={[{ ...ONE, status: "CANCELLED" }]} nextCursor={null} firstPage />);

    expect(screen.getByText(/cancelled/i)).toBeInTheDocument();
  });

  it("says one passenger, not 1 passengers", () => {
    render(<MyBookings bookings={[{ ...ONE, passengerCount: 1 }]} nextCursor={null} firstPage />);

    expect(screen.getByText(/1 passenger\b/)).toBeInTheDocument();
  });

  it("explains an empty list and how a booking gets here", () => {
    render(<MyBookings bookings={[]} nextCursor={null} firstPage />);

    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
    expect(screen.getByText(/no bookings/i)).toBeInTheDocument();
    expect(screen.getByText(/email address/i)).toBeInTheDocument();
  });

  it("links to the older bookings, carrying the cursor in the address", () => {
    render(<MyBookings bookings={[ONE]} nextCursor="a-b_c" firstPage />);

    expect(screen.getByRole("link", { name: /older bookings/i })).toHaveAttribute("href", "/account?cursor=a-b_c");
    expect(screen.queryByRole("link", { name: /newest bookings/i })).not.toBeInTheDocument();
  });

  it("makes a cursor safe to put in an address", () => {
    render(<MyBookings bookings={[ONE]} nextCursor="a b&c=d" firstPage />);

    expect(screen.getByRole("link", { name: /older bookings/i })).toHaveAttribute("href", "/account?cursor=a%20b%26c%3Dd");
  });

  it("offers a way back to the newest bookings from a later page, and no older link on the last page", () => {
    render(<MyBookings bookings={[ONE]} nextCursor={null} firstPage={false} />);

    expect(screen.getByRole("link", { name: /newest bookings/i })).toHaveAttribute("href", "/account");
    expect(screen.queryByRole("link", { name: /older bookings/i })).not.toBeInTheDocument();
  });

  it("explains an empty later page rather than saying the traveller has no bookings", () => {
    render(<MyBookings bookings={[]} nextCursor={null} firstPage={false} />);

    expect(screen.getByText(/no older bookings/i)).toBeInTheDocument();
    expect(screen.queryByText(/no bookings yet/i)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /newest bookings/i })).toBeInTheDocument();
  });
});
