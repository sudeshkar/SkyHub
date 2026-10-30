import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DisruptionForm } from "./disruption-form";
import { recordDisruption } from "./actions";

vi.mock("./actions", () => ({ recordDisruption: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

/** Submits the form directly: a button click would be stopped by the browser's own "required" checks. */
function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Record" }).closest("form")!);
}

describe("DisruptionForm", () => {
  it("asks for the new time only when the flight is delayed", async () => {
    render(<DisruptionForm />);

    expect(screen.getByLabelText(/New departure date and time/)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText("Cancelled"));

    expect(screen.queryByLabelText(/New departure date and time/)).not.toBeInTheDocument();
  });

  it("tells staff how many bookings a recorded disruption touches", async () => {
    vi.mocked(recordDisruption).mockResolvedValue({
      status: "recorded", flightNumber: "SK501", departureDate: "2026-11-02", type: "CANCELLED", affectedBookings: 42,
    });
    render(<DisruptionForm />);

    fireEvent.change(screen.getByLabelText("Flight number"), { target: { value: "SK501" } });
    fireEvent.click(screen.getByLabelText("Cancelled"));
    submit();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("SK501 on 2026-11-02 is recorded as cancelled.");
    expect(notice).toHaveTextContent("42 active bookings are on this flight.");
  });

  it("uses the singular for one booking and says when there are none", async () => {
    vi.mocked(recordDisruption).mockResolvedValueOnce({
      status: "recorded", flightNumber: "SK501", departureDate: "2026-11-02", type: "DELAYED", affectedBookings: 1,
    });
    render(<DisruptionForm />);

    submit();

    expect(await screen.findByRole("status")).toHaveTextContent("1 active booking is on this flight.");
  });

  it("shows why a request was refused and keeps what was typed", async () => {
    vi.mocked(recordDisruption).mockResolvedValue({
      status: "error",
      message: "Enter a flight number such as SK501.",
      values: { flightNumber: "BAD", departureDate: "2026-11-02", type: "DELAYED", revisedDeparture: "", reason: "Weather" },
    });
    render(<DisruptionForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Enter a flight number such as SK501.");
    expect(screen.getByLabelText("Flight number")).toHaveValue("BAD");
    expect(screen.getByLabelText(/Reason shown to travellers/)).toHaveValue("Weather");
  });

  it("sends staff back to sign in when the session has ended", async () => {
    vi.mocked(recordDisruption).mockResolvedValue({ status: "signed-out" });
    render(<DisruptionForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Your session has ended");
    expect(screen.getByRole("link", { name: "Sign in again" })).toHaveAttribute("href", "/ops");
  });
});
