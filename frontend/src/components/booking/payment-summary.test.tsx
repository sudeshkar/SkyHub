import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PaymentSummary } from "./payment-summary";

const QUOTE = { kind: "quoted" as const, amount: 861, currency: "USD" };

describe("PaymentSummary", () => {
  it("shows the total the server worked out, to the cent", () => {
    render(<PaymentSummary quote={QUOTE} method="tok_visa" onMethodChange={vi.fn()} />);

    expect(screen.getByText("$861.00")).toBeInTheDocument();
  });

  it("says it is working out the price while waiting", () => {
    render(<PaymentSummary quote={null} method="tok_visa" onMethodChange={vi.fn()} />);

    expect(screen.getByText("Working out the price…")).toBeInTheDocument();
  });

  it("says plainly when the price could not be worked out", () => {
    render(<PaymentSummary quote={{ kind: "unavailable" }} method="tok_visa" onMethodChange={vi.fn()} />);

    expect(screen.getByRole("alert")).toHaveTextContent("couldn't work out the price");
  });

  it("offers the demo payment methods and selects the chosen one", () => {
    render(<PaymentSummary quote={QUOTE} method="tok_visa" onMethodChange={vi.fn()} />);

    expect(screen.getByRole("radio", { name: /ending 4242/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /declined/i })).not.toBeChecked();
    expect(screen.getByText(/no real card/i)).toBeInTheDocument();
  });

  it("reports the method the traveller picks", () => {
    const onMethodChange = vi.fn();
    render(<PaymentSummary quote={QUOTE} method="tok_visa" onMethodChange={onMethodChange} />);

    fireEvent.click(screen.getByRole("radio", { name: /declined/i }));

    expect(onMethodChange).toHaveBeenCalledWith("tok_declined");
  });

  it("locks the choice while a payment is being made", () => {
    render(<PaymentSummary quote={QUOTE} method="tok_visa" onMethodChange={vi.fn()} disabled />);

    for (const radio of screen.getAllByRole("radio")) expect(radio).toBeDisabled();
  });
});
