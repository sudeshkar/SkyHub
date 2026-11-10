import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Seat } from "@/lib/api/booking";
import { SeatMap } from "./seat-map";

const seats: Seat[] = [
  { seatNumber: "1A", row: 1, letter: "A", cabin: "BUSINESS", status: "AVAILABLE" },
  { seatNumber: "11D", row: 11, letter: "D", cabin: "ECONOMY", status: "AVAILABLE" },
  { seatNumber: "11E", row: 11, letter: "E", cabin: "ECONOMY", status: "BOOKED" },
  { seatNumber: "11F", row: 11, letter: "F", cabin: "ECONOMY", status: "AVAILABLE" },
];

describe("SeatMap", () => {
  it("lets a passenger choose an available seat", () => {
    const onSelect = vi.fn();
    render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: "Seat 11D, economy, available" }));

    expect(onSelect).toHaveBeenCalledWith("11D");
  });

  it("disables seats that are already booked", () => {
    render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Seat 11E, economy, taken" })).toBeDisabled();
  });

  it("disables seats another traveller is holding at the moment", () => {
    const held: Seat[] = [{ seatNumber: "11D", row: 11, letter: "D", cabin: "ECONOMY", status: "HELD" }];
    render(<SeatMap seats={held} heldByParty={new Map()} onSelect={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Seat 11D, economy, held by another traveller" })).toBeDisabled();
  });

  it("disables seats chosen for another passenger in the same booking", () => {
    render(<SeatMap seats={seats} heldByParty={new Map([["11F", "RP"]])} onSelect={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Seat 11F, economy, chosen for another passenger" })).toBeDisabled();
  });

  it("marks the current passenger's seat as pressed", () => {
    render(<SeatMap seats={seats} selected="1A" heldByParty={new Map()} onSelect={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Seat 1A, business, selected" })).toHaveAttribute("aria-pressed", "true");
  });

  it("labels the cabins", () => {
    render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

    expect(screen.getByText("Business")).toBeInTheDocument();
    expect(screen.getByText("Economy")).toBeInTheDocument();
  });

  it("explains what each kind of seat looks like", () => {
    render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

    const legend = screen.getByRole("list", { name: "Seat key" });
    expect(legend).toHaveTextContent("Available");
    expect(legend).toHaveTextContent("Your seat");
    expect(legend).toHaveTextContent("Taken");
  });

  describe("when the rows are wider than the screen", () => {
    beforeEach(() => {
      vi.spyOn(HTMLElement.prototype, "scrollWidth", "get").mockReturnValue(1200);
      vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(600);
      HTMLElement.prototype.scrollBy = vi.fn();
      HTMLElement.prototype.scrollTo = vi.fn();
    });
    afterEach(() => vi.restoreAllMocks());

    it("offers arrows, starting at the front of the plane", () => {
      render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

      expect(screen.getByRole("button", { name: "Show rows nearer the front" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Show rows further back" })).toBeEnabled();
    });

    it("moves most of a screen further back with the arrow", () => {
      render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

      fireEvent.click(screen.getByRole("button", { name: "Show rows further back" }));

      expect(HTMLElement.prototype.scrollBy).toHaveBeenCalledWith(expect.objectContaining({ left: 480 }));
    });

    it("jumps straight to a cabin", () => {
      render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

      fireEvent.click(screen.getByRole("button", { name: "Economy, rows 11–11" }));

      expect(HTMLElement.prototype.scrollTo).toHaveBeenCalled();
    });
  });

  it("shows no arrows when every row fits on the screen", () => {
    render(<SeatMap seats={seats} heldByParty={new Map()} onSelect={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Show rows further back" })).not.toBeInTheDocument();
  });
});
