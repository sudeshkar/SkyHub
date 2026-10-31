import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { gateScan } from "./actions";
import { GatePanel } from "./gate-panel";

vi.mock("./actions", () => ({ gateScan: vi.fn() }));

const PASS = { passengerName: "PERERA/PRIYA", flightNumber: "SK501", departureDate: "2026-11-02", seatNumber: "11D", sequence: 7 };

beforeEach(() => {
  vi.resetAllMocks();
});

/** Submits the panel's form from the named button (the action itself is mocked, so which button only matters for the page's real form data). */
function press(name: string) {
  fireEvent.submit(screen.getByRole("button", { name }).closest("form")!);
}

describe("GatePanel (ADR-0062)", () => {
  it("asks for the scanned pass and offers to check it or to board the passenger", () => {
    render(<GatePanel />);

    expect(screen.getByLabelText("Scanned boarding pass")).toBeRequired();
    expect(screen.getByRole("button", { name: "Check pass" })).toHaveAttribute("value", "check");
    expect(screen.getByRole("button", { name: "Board passenger" })).toHaveAttribute("value", "board");
  });

  it("says plainly that checking a pass does not board anyone", () => {
    render(<GatePanel />);

    expect(screen.getByText(/checking a pass does not board the passenger/i)).toBeInTheDocument();
  });

  it("shows who and where for a pass that is good, and says whether they have boarded", async () => {
    vi.mocked(gateScan).mockResolvedValue({ status: "good", intent: "check", pass: PASS });
    render(<GatePanel />);

    press("Check pass");

    const result = await screen.findByRole("status");
    expect(result).toHaveTextContent("PERERA/PRIYA");
    expect(result).toHaveTextContent("SK501");
    expect(result).toHaveTextContent("2026-11-02");
    expect(result).toHaveTextContent("11D");
    expect(result).toHaveTextContent("7");
    expect(result).toHaveTextContent(/good pass/i);
    expect(result).not.toHaveTextContent(/has boarded/i);
  });

  it("says the passenger has boarded after boarding", async () => {
    vi.mocked(gateScan).mockResolvedValue({ status: "good", intent: "board", pass: PASS });
    render(<GatePanel />);

    press("Board passenger");

    const result = await screen.findByRole("status");
    expect(result).toHaveTextContent("PERERA/PRIYA has boarded");
    expect(result).toHaveTextContent("SK501");
  });

  it.each([
    ["BAD_SIGNATURE", /not a SkyHub boarding pass|has been changed/i],
    ["UNKNOWN_BOOKING", /no booking/i],
    ["BOOKING_CANCELLED", /booking was cancelled/i],
    ["FLIGHT_CANCELLED", /flight was cancelled/i],
    ["NOT_CHECKED_IN", /not checked in/i],
    ["SUPERSEDED", /old pass/i],
    ["ALREADY_BOARDED", /already boarded/i],
  ] as const)("refuses a pass for %s in plain words, and says nothing about the passenger", async (reason, words) => {
    vi.mocked(gateScan).mockResolvedValue({ status: "refused", reason });
    render(<GatePanel />);

    press("Board passenger");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(words);
    expect(alert).toHaveTextContent(/do not board|send them|ask for/i);
    expect(alert).not.toHaveTextContent(/PERERA/);
  });

  it("shows a problem with the request or the service as it was given", async () => {
    vi.mocked(gateScan).mockResolvedValue({ status: "error", message: "The booking service is not responding right now. Please try again." });
    render(<GatePanel />);

    press("Check pass");

    expect(await screen.findByRole("alert")).toHaveTextContent("not responding");
  });

  it("tells a person whose session has ended to sign in again", async () => {
    vi.mocked(gateScan).mockResolvedValue({ status: "signed-out" });
    render(<GatePanel />);

    press("Check pass");

    expect(await screen.findByRole("alert")).toHaveTextContent(/sign in again/i);
  });
});
