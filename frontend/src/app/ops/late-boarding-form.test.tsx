import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { boardLate } from "./actions";
import { LateBoardingForm } from "./late-boarding-form";

vi.mock("./actions", () => ({ boardLate: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Board late" }).closest("form")!);
}

describe("LateBoardingForm (ADR-0076)", () => {
  it("says plainly what it is for and that it is recorded, and asks for the pass, a reason and a confirmation", () => {
    render(<LateBoardingForm />);

    expect(screen.getByText(/only for a passenger who reaches the gate after the gate has closed/i)).toBeInTheDocument();
    expect(screen.getByText(/recorded/i)).toBeInTheDocument();
    expect(screen.getByLabelText("Boarding pass")).toBeRequired();
    expect(screen.getByLabelText("Reason")).toBeRequired();
    expect(screen.getByLabelText("Reason")).toHaveAttribute("maxlength", "200");
    expect(screen.getByLabelText(/I am sure/)).toBeRequired();
  });

  it("says who has boarded, late, and that the reason was recorded", async () => {
    vi.mocked(boardLate).mockResolvedValue({
      status: "boarded", pass: { passengerName: "PERERA/PRIYA", flightNumber: "SK501", departureDate: "2026-11-02", seatNumber: "11D", sequence: 7 },
    });
    render(<LateBoardingForm />);

    submit();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("PERERA/PRIYA has been boarded late");
    expect(notice).toHaveTextContent("SK501");
    expect(notice).toHaveTextContent(/reason were recorded/i);
  });

  it.each([
    ["BOARDING_STILL_OPEN", /still open/i],
    ["BOARDING_NOT_OPEN", /not opened/i],
    ["FLIGHT_DEPARTED", /due to have left/i],
    ["ALREADY_BOARDED", /already boarded/i],
    ["BAD_SIGNATURE", /not a skyhub boarding pass/i],
  ] as const)("says in plain words why a pass was refused (%s), and nothing about the passenger", async (reason, words) => {
    vi.mocked(boardLate).mockResolvedValue({ status: "refused", reason });
    render(<LateBoardingForm />);

    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(words);
    expect(alert).not.toHaveTextContent(/PERERA/);
  });

  it("shows a problem with the request as it was given", async () => {
    vi.mocked(boardLate).mockResolvedValue({ status: "error", message: "Give a reason of up to 200 characters." });
    render(<LateBoardingForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("Give a reason of up to 200 characters.");
  });

  it("sends the person back to sign in when the session ended or the role cannot do this", async () => {
    vi.mocked(boardLate).mockResolvedValue({ status: "signed-out" });
    render(<LateBoardingForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(/session has ended|role cannot/i);
  });
});
