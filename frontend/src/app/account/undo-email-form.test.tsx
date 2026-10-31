import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { undoEmailChange } from "./email-actions";
import { UndoEmailForm } from "./undo-email-form";

vi.mock("./email-actions", () => ({ undoEmailChange: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function press() {
  fireEvent.submit(screen.getByRole("button", { name: "Take my account back" }).closest("form")!);
}

describe("UndoEmailForm", () => {
  it("carries the token from the link in a hidden field, never in text on the page", () => {
    const { container } = render(<UndoEmailForm token="the-token" />);

    expect(container.querySelector('input[name="token"]')).toHaveValue("the-token");
    expect(container).not.toHaveTextContent("the-token");
  });

  it("says what pressing the button does before the person presses it", () => {
    render(<UndoEmailForm token="t" />);

    expect(screen.getByText(/put your old email address back/i)).toBeInTheDocument();
    expect(screen.getByText(/sign everyone out/i)).toBeInTheDocument();
    expect(screen.getByText(/password is removed/i)).toBeInTheDocument();
    expect(screen.getByText(/if you changed your address yourself, do not press/i)).toBeInTheDocument();
  });

  it("tells the person it worked and that a link to choose a new password is on its way", async () => {
    vi.mocked(undoEmailChange).mockResolvedValue({ status: "done" });
    render(<UndoEmailForm token="t" />);

    press();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent(/account is yours again/i);
    expect(notice).toHaveTextContent(/link to choose a new password/i);
  });

  it("says a wrong, used or expired link has stopped working and who to ask", async () => {
    vi.mocked(undoEmailChange).mockResolvedValue({ status: "link-invalid" });
    render(<UndoEmailForm token="old" />);

    press();

    expect(await screen.findByRole("alert")).toHaveTextContent(/no longer works/i);
    expect(screen.getByRole("alert")).toHaveTextContent(/support/i);
  });

  it("shows the wait or the outage message it was given", async () => {
    vi.mocked(undoEmailChange).mockResolvedValue({ status: "error", message: "Please try again." });
    render(<UndoEmailForm token="t" />);

    press();

    expect(await screen.findByRole("alert")).toHaveTextContent("Please try again.");
  });
});
