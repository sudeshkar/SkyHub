import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { WithdrawForm } from "./withdraw-form";
import { withdrawDisruption } from "./actions";

vi.mock("./actions", () => ({ withdrawDisruption: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Withdraw" }).closest("form")!);
}

describe("WithdrawForm", () => {
  it("makes the person confirm, and says plainly what withdrawing does and does not do", () => {
    render(<WithdrawForm />);

    expect(screen.getByLabelText(/I am sure/)).toBeRequired();
    expect(screen.getByText(/not emailed again/i)).toBeInTheDocument();
  });

  it("tells staff how many bookings are on the flight once the disruption is withdrawn", async () => {
    vi.mocked(withdrawDisruption).mockResolvedValue({
      status: "withdrawn", flightNumber: "SK501", departureDate: "2026-11-02", affectedBookings: 12,
    });
    render(<WithdrawForm />);

    fireEvent.change(screen.getByLabelText("Flight number"), { target: { value: "SK501" } });
    submit();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("SK501 on 2026-11-02 is back to its schedule.");
    expect(notice).toHaveTextContent("12 active bookings");
  });

  it("shows the reason when the service refuses", async () => {
    vi.mocked(withdrawDisruption).mockResolvedValue({
      status: "error", message: "Bookings were moved off SK501, so its disruption has to stay on record",
      values: { flightNumber: "SK501", departureDate: "2026-11-02" },
    });
    render(<WithdrawForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("has to stay on record");
    expect(screen.getByLabelText("Flight number")).toHaveValue("SK501");
  });

  it("sends the person to sign in again when the session has ended", async () => {
    vi.mocked(withdrawDisruption).mockResolvedValue({ status: "signed-out" });
    render(<WithdrawForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Sign in again");
  });
});
