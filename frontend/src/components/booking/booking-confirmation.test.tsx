import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { forgetRecentBooking, rememberBooking } from "@/lib/recent-booking";
import { ConfirmedView } from "./booking-confirmation";

afterEach(() => forgetRecentBooking());

describe("ConfirmedView", () => {
  it("shows the booking just made in this tab, so a refresh or Forward still shows the confirmation", () => {
    rememberBooking({
      locator: "ST3X9Q",
      contactEmail: "t****@example.com",
      paid: "$850.00",
      trip: "SK501_2026-11-14,SK001_2026-11-14",
      passengers: [{ firstName: "Test", lastName: "Traveller", seats: ["1F", "2F"] }],
    });

    render(<ConfirmedView />);

    expect(screen.getByLabelText("Booking reference S T 3 X 9 Q")).toBeInTheDocument();
    expect(screen.getByText("$850.00")).toBeInTheDocument();
    expect(screen.getByText(/Confirmation sent to t\*\*\*\*@example.com/)).toBeInTheDocument();
    expect(screen.getByText(/Test Traveller/)).toHaveTextContent("1F, 2F");
    expect(screen.getByRole("link", { name: "Manage this booking" })).toHaveAttribute("href", "/manage");
  });

  it("points to Manage booking when there is nothing to show in this tab", () => {
    render(<ConfirmedView />);

    expect(screen.queryByLabelText(/Booking reference/)).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Manage booking" })).toHaveAttribute("href", "/manage");
    expect(screen.getByRole("link", { name: "Book a flight" })).toHaveAttribute("href", "/");
  });
});
