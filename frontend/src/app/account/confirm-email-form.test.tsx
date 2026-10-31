import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConfirmEmailForm } from "./confirm-email-form";
import { confirmEmailChange } from "./email-actions";

vi.mock("./email-actions", () => ({ confirmEmailChange: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function confirm() {
  fireEvent.submit(screen.getByRole("button", { name: "Confirm the change" }).closest("form")!);
}

describe("ConfirmEmailForm", () => {
  it("carries the token from the link in a hidden field, never in text on the page", () => {
    const { container } = render(<ConfirmEmailForm token="the-token" />);

    expect(container.querySelector('input[name="token"]')).toHaveValue("the-token");
    expect(container).not.toHaveTextContent("the-token");
  });

  it("says what confirming does before the person presses the button", () => {
    render(<ConfirmEmailForm token="t" />);

    expect(screen.getByText(/sign in again with the new address/i)).toBeInTheDocument();
    expect(screen.getByText(/every session on this account, including this one, will end/i)).toBeInTheDocument();
    expect(screen.getByText(/bookings made with the old address/i)).toBeInTheDocument();
  });

  it("tells the person it worked and to sign in with the new address next time", async () => {
    vi.mocked(confirmEmailChange).mockResolvedValue({ status: "done" });
    render(<ConfirmEmailForm token="t" />);

    confirm();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent(/new email address is saved/i);
    expect(notice).toHaveTextContent(/signed out/i);
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/account");
  });

  it("says a wrong, used or expired link has stopped working and how to get another", async () => {
    vi.mocked(confirmEmailChange).mockResolvedValue({ status: "link-invalid" });
    render(<ConfirmEmailForm token="old" />);

    confirm();

    expect(await screen.findByRole("alert")).toHaveTextContent(/no longer works/i);
    expect(screen.getByRole("link", { name: /change email address/i })).toHaveAttribute("href", "/account/change-email");
  });

  it("asks a signed-out person to sign in and then open the link again", async () => {
    vi.mocked(confirmEmailChange).mockResolvedValue({ status: "signed-out" });
    render(<ConfirmEmailForm token="t" />);

    confirm();

    expect(await screen.findByRole("alert")).toHaveTextContent(/sign in.*open the link/i);
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/account");
  });

  it("shows another problem as it is", async () => {
    vi.mocked(confirmEmailChange).mockResolvedValue({ status: "error", message: "The booking service is not responding right now. Please try again." });
    render(<ConfirmEmailForm token="t" />);

    confirm();

    expect(await screen.findByRole("alert")).toHaveTextContent("not responding");
  });
});
