import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { BoardingPass } from "@/lib/api/booking";
import { BoardingPassCard } from "./boarding-pass-card";

const PASS: BoardingPass = {
  passengerName: "PERERA/PRIYA", passengerIndex: 0, locator: "ABC234", flightNumber: "SK501", departureDate: "2026-11-03",
  seatNumber: "11D", sequence: 7, barcode: "M1PERERA/PRIYA ABC234 SK501 2026-11-03 11D 0007.Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbA",
};

describe("BoardingPassCard (ADR-0071)", () => {
  it("shows a QR code of the pass beside the details a person reads", () => {
    render(<BoardingPassCard pass={PASS} />);

    const card = screen.getByRole("article");
    expect(within(card).getByRole("img", { name: /QR code.*PERERA\/PRIYA/ })).toBeInTheDocument();
    expect(card).toHaveTextContent("PERERA/PRIYA");
    expect(card).toHaveTextContent("Boarding no. 7");
    expect(card).toHaveTextContent("SK501 · 2026-11-03 · Seat 11D");
  });

  it("keeps the pass text under the code, so it can be copied when a camera cannot be used", () => {
    render(<BoardingPassCard pass={PASS} />);

    expect(screen.getByText(PASS.barcode)).toBeInTheDocument();
  });

  it("gives each pass its own code", () => {
    const { container, rerender } = render(<BoardingPassCard pass={PASS} />);
    const first = container.querySelector("path")?.getAttribute("d");

    rerender(<BoardingPassCard pass={{ ...PASS, seatNumber: "11E", barcode: PASS.barcode.replace("11D", "11E") }} />);

    expect(container.querySelector("path")?.getAttribute("d")).not.toBe(first);
  });
});
