import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BoardedPanel } from "./boarded-panel";
import { loadFlightBoarding } from "./actions";

vi.mock("./actions", () => ({ loadFlightBoarding: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Show boarding" }).closest("form")!);
}

const LOADED = {
  status: "loaded" as const,
  flightNumber: "SK501",
  departureDate: "2026-11-03",
  checkedIn: 3,
  boarded: 1,
  waiting: 2,
  rows: [{ locator: "ABC234", leg: 1, passenger: 2, by: "gate.one", at: "2026-11-03T09:00:00Z" }],
};

describe("BoardedPanel (ADR-0065)", () => {
  it("asks for a flight and a date", () => {
    render(<BoardedPanel />);

    expect(screen.getByLabelText("Flight number")).toBeRequired();
    expect(screen.getByLabelText(/departure date/i)).toBeRequired();
  });

  it("shows how many are checked in, boarded and waiting, and a row for each boarding with the numbers the undo form asks for", async () => {
    vi.mocked(loadFlightBoarding).mockResolvedValue(LOADED);
    render(<BoardedPanel />);

    submit();

    const summary = await screen.findByRole("status");
    expect(summary).toHaveTextContent("SK501 on 2026-11-03");
    expect(summary).toHaveTextContent(/3 checked in/i);
    expect(summary).toHaveTextContent(/1 boarded/i);
    expect(summary).toHaveTextContent(/2 waiting/i);
    const row = within(screen.getByRole("table")).getByText("ABC234").closest("tr")!;
    expect(row).toHaveTextContent(/flight 1/i);
    expect(row).toHaveTextContent(/passenger 2/i);
    expect(row).toHaveTextContent("gate.one");
  });

  it("says plainly when nobody has boarded, and shows no table", async () => {
    vi.mocked(loadFlightBoarding).mockResolvedValue({ ...LOADED, checkedIn: 0, boarded: 0, waiting: 0, rows: [] });
    render(<BoardedPanel />);

    submit();

    expect(await screen.findByText(/nobody has boarded/i)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows why it could not load and keeps what was typed", async () => {
    vi.mocked(loadFlightBoarding).mockResolvedValue({
      status: "error",
      message: "The booking service is not responding right now. Please try again.",
      values: { flightNumber: "SK501", departureDate: "2026-11-03" },
    });
    render(<BoardedPanel />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("not responding");
    expect(screen.getByLabelText("Flight number")).toHaveValue("SK501");
  });

  it("sends the person back to sign in when the session ended or the role cannot do this", async () => {
    vi.mocked(loadFlightBoarding).mockResolvedValue({ status: "signed-out" });
    render(<BoardedPanel />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(/session has ended|role cannot/i);
  });
});
