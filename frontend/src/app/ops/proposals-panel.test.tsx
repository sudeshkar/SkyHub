import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { loadProposals, moveBookings } from "./actions";
import { ProposalsPanel } from "./proposals-panel";

vi.mock("./actions", () => ({ loadProposals: vi.fn(), moveBookings: vi.fn() }));

const proposal = (locator: string, passengerCount = 1) => ({
  locator, action: "REBOOK" as const, flightNumber: "SK503", departureDate: "2026-11-02", segmentIndex: 0, passengerCount,
  rationale: "Earliest flight that fits.",
});
const human = (locator: string) => ({
  locator, action: "NEEDS_HUMAN" as const, flightNumber: null, departureDate: null, segmentIndex: null, passengerCount: 3,
  rationale: "only 0 seats left on SK503, 3 needed",
});
const set = (...proposals: ReturnType<typeof proposal | typeof human>[]) => ({
  planner: "first-fit", flightNumber: "SK501", departureDate: "2026-11-02", bookingsTotal: proposals.length,
  rebooked: proposals.filter((p) => p.action === "REBOOK").length, proposals,
});

function showProposals(...proposals: ReturnType<typeof proposal | typeof human>[]) {
  vi.mocked(loadProposals).mockResolvedValue({ status: "loaded", set: set(...proposals) });
  render(<ProposalsPanel />);
  fireEvent.submit(screen.getByRole("button", { name: "Get proposals" }).closest("form")!);
}

beforeEach(() => {
  vi.resetAllMocks();
});

describe("ProposalsPanel", () => {
  it("lists each proposed move with its size and reason, and who decided", async () => {
    showProposals(proposal("ABC234", 2), human("DEF567"));

    expect(await screen.findByText("ABC234")).toBeInTheDocument();
    expect(screen.getByText(/SK503 on 2026-11-02/)).toBeInTheDocument();
    expect(screen.getByText(/2 passengers/)).toBeInTheDocument();
    expect(screen.getByText(/only 0 seats left on SK503/)).toBeInTheDocument();
    expect(screen.getByText(/rule-based/i)).toBeInTheDocument();
  });

  it("offers a checkbox only for moves, all ticked to begin with, and none for bookings that need a person", async () => {
    showProposals(proposal("ABC234"), proposal("GHI890"), human("DEF567"));

    await screen.findByText("ABC234");

    const boxes = screen.getAllByRole("checkbox", { name: /Move / });
    expect(boxes).toHaveLength(2);
    boxes.forEach((box) => expect(box).toBeChecked());
    expect(screen.getByRole("button", { name: "Move 2 selected" })).toBeEnabled();
  });

  it("moves only what is still ticked, and shows the outcome of each", async () => {
    vi.mocked(moveBookings).mockResolvedValue({ status: "done", results: [{ locator: "GHI890", status: "moved", seats: ["14A"] }] });
    showProposals(proposal("ABC234"), proposal("GHI890"));
    await screen.findByText("ABC234");

    fireEvent.click(screen.getByRole("checkbox", { name: "Move ABC234" }));
    fireEvent.click(screen.getByRole("button", { name: "Move 1 selected" }));

    expect(await screen.findByText(/Moved/)).toBeInTheDocument();
    expect(moveBookings).toHaveBeenCalledWith([{ locator: "GHI890", segmentIndex: 0, flightNumber: "SK503", departureDate: "2026-11-02" }]);
  });

  it("sends a long list in batches of twenty, one after another, and says how far it has got", async () => {
    const many = Array.from({ length: 45 }, (_, i) => proposal(`B${String(i).padStart(5, "0")}`));
    vi.mocked(moveBookings).mockImplementation(async (moves) => ({
      status: "done", results: moves.map((m) => ({ locator: m.locator, status: "moved" as const, seats: ["1A"] })) }));
    showProposals(...many);
    await screen.findByText("B00000");

    fireEvent.click(screen.getByRole("button", { name: "Move 45 selected" }));

    await waitFor(() => expect(moveBookings).toHaveBeenCalledTimes(3));
    expect(vi.mocked(moveBookings).mock.calls.map(([batch]) => batch.length)).toEqual([20, 20, 5]);
    expect(await screen.findByText(/45 moved/)).toBeInTheDocument();
  });

  it("shows why a move was refused, and leaves that booking selectable for another try", async () => {
    vi.mocked(moveBookings).mockResolvedValue({ status: "done", results: [
      { locator: "ABC234", status: "refused", detail: "SK503 flies DXB to JFK, not the booked route DXB to LHR" }] });
    showProposals(proposal("ABC234"));
    await screen.findByText("ABC234");

    fireEvent.click(screen.getByRole("button", { name: "Move 1 selected" }));

    expect(await screen.findByText(/not the booked route DXB to LHR/)).toBeInTheDocument();
  });

  it("stops and asks for a new sign-in when the session has ended partway through", async () => {
    vi.mocked(moveBookings).mockResolvedValue({ status: "signed-out", results: [] });
    showProposals(proposal("ABC234"));
    await screen.findByText("ABC234");

    fireEvent.click(screen.getByRole("button", { name: "Move 1 selected" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("session has ended");
  });

  it("shows an error from the agent and keeps what was typed", async () => {
    vi.mocked(loadProposals).mockResolvedValue({
      status: "error", message: "The agent is busy right now. Try again in a moment.", values: { flightNumber: "SK501", departureDate: "2026-11-02" } });
    render(<ProposalsPanel />);

    fireEvent.submit(screen.getByRole("button", { name: "Get proposals" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent("busy");
    expect(screen.getByLabelText("Disrupted flight")).toHaveValue("SK501");
  });

  it("says plainly when there is nothing to move", async () => {
    showProposals();

    expect(await screen.findByText(/No active bookings/)).toBeInTheDocument();
    expect(within(document.body).queryByRole("button", { name: /^Move/ })).not.toBeInTheDocument();
  });
});
