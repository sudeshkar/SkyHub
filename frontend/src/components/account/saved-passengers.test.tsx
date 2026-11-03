import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SavedPassengers } from "./saved-passengers";

vi.mock("@/app/account/saved-passenger-actions", () => ({ addSavedPassenger: vi.fn(), deleteSavedPassenger: vi.fn() }));

const two = {
  kind: "list" as const, limit: 10,
  passengers: [{ id: 3, firstName: "Priya", lastName: "Perera" }, { id: 4, firstName: "Raj", lastName: "Perera" }],
};

describe("SavedPassengers (ADR-0078)", () => {
  it("lists each saved name with a button to remove it, and says how many of the places are used", () => {
    render(<SavedPassengers result={two} />);

    expect(screen.getByText("Priya Perera")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove Raj Perera" })).toBeInTheDocument();
    expect(screen.getByText(/2 of 10/)).toBeInTheDocument();
  });

  it("removes by the id in a form post, so no name is ever in an address", () => {
    const { container } = render(<SavedPassengers result={two} />);

    const row = screen.getByText("Priya Perera").closest("li")!;
    expect(within(row).getByDisplayValue("3")).toHaveAttribute("name", "id");
    expect(container.querySelectorAll("a")).toHaveLength(0);
  });

  it("says plainly that only names are kept", () => {
    render(<SavedPassengers result={two} />);

    expect(screen.getByText(/only the names/i)).toBeInTheDocument();
  });

  it("asks for a first and a last name to add one, each up to fifty characters", () => {
    render(<SavedPassengers result={two} />);

    expect(screen.getByLabelText("First name")).toBeRequired();
    expect(screen.getByLabelText("Last name")).toHaveAttribute("maxlength", "50");
    expect(screen.getByRole("button", { name: "Save passenger" })).toBeEnabled();
  });

  it("says there are none yet, and what they are for", () => {
    render(<SavedPassengers result={{ kind: "list", limit: 10, passengers: [] }} />);

    expect(screen.getByText(/no saved passengers yet/i)).toBeInTheDocument();
    expect(screen.getByText(/fill in a booking/i)).toBeInTheDocument();
  });

  it("stops offering to add when all ten places are used, and says so", () => {
    const ten = { kind: "list" as const, limit: 10, passengers: Array.from({ length: 10 }, (_, i) => ({ id: i + 1, firstName: `N${i}`, lastName: "Perera" })) };
    render(<SavedPassengers result={ten} />);

    expect(screen.getByText(/all 10 places are used/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save passenger" })).not.toBeInTheDocument();
  });

  it("says in plain words when the names cannot be shown, and shows no form to guess at", () => {
    const { rerender } = render(<SavedPassengers result={{ kind: "unavailable" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent(/could not be loaded/i);

    rerender(<SavedPassengers result={{ kind: "unauthorized" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent(/session has ended/i);
    expect(screen.queryByRole("button", { name: "Save passenger" })).not.toBeInTheDocument();
  });

  it.each([
    ["passenger-invalid", /name of 1 to 50 characters/i],
    ["passengers-full", /all 10 places/i],
    ["passenger-failed", /could not be done/i],
  ])("shows what went wrong for %s", (problem, words) => {
    render(<SavedPassengers result={two} problem={problem} />);

    expect(screen.getByRole("alert")).toHaveTextContent(words);
  });

  it("ignores a problem that is not about saved passengers", () => {
    render(<SavedPassengers result={two} problem="wait" />);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
