import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UndoBoardingForm } from "./undo-boarding-form";
import { undoBoarding } from "./actions";

vi.mock("./actions", () => ({ undoBoarding: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Undo boarding" }).closest("form")!);
}

describe("UndoBoardingForm (ADR-0064)", () => {
  it("says plainly what undoing does: the passenger can board again, and the correction is recorded with a reason", () => {
    render(<UndoBoardingForm />);

    expect(screen.getByText(/can board again/i)).toBeInTheDocument();
    expect(screen.getByText(/recorded/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/I am sure/)).toBeRequired();
    expect(screen.getByLabelText("Reason")).toBeRequired();
    expect(screen.getByLabelText("Reason")).toHaveAttribute("maxlength", "200");
  });

  it("confirms with the reference and the numbers, counting from one", async () => {
    vi.mocked(undoBoarding).mockResolvedValue({ status: "undone", locator: "ABC234", leg: 1, passenger: 2 });
    render(<UndoBoardingForm />);

    fireEvent.change(screen.getByLabelText("Booking reference"), { target: { value: "ABC234" } });
    submit();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("ABC234");
    expect(notice).toHaveTextContent(/passenger 2/i);
    expect(notice).toHaveTextContent(/flight 1/i);
    expect(notice).toHaveTextContent(/can board again/i);
  });

  it("shows why it was refused and keeps what was typed", async () => {
    vi.mocked(undoBoarding).mockResolvedValue({
      status: "error",
      message: "That passenger has not boarded this flight, so there is nothing to undo",
      values: { locator: "ABC234", leg: "1", passenger: "2", reason: "wrong person" },
    });
    render(<UndoBoardingForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("nothing to undo");
    expect(screen.getByLabelText("Booking reference")).toHaveValue("ABC234");
    expect(screen.getByLabelText("Reason")).toHaveValue("wrong person");
  });

  it("sends the person back to sign in when the session ended or the role cannot do this", async () => {
    vi.mocked(undoBoarding).mockResolvedValue({ status: "signed-out" });
    render(<UndoBoardingForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(/session has ended|role cannot/i);
  });
});
