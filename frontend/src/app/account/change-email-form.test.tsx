import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChangeEmailForm } from "./change-email-form";
import { requestEmailChange } from "./email-actions";

vi.mock("./email-actions", () => ({ requestEmailChange: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Email me a link" }).closest("form")!);
}

describe("ChangeEmailForm", () => {
  it("asks for the new address and the current password", () => {
    render(<ChangeEmailForm />);

    expect(screen.getByLabelText("New email address")).toBeInTheDocument();
    expect(screen.getByLabelText("Current password")).toHaveAttribute("type", "password");
  });

  it("says the same thing whether or not the new address can be used, and what happens next", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "sent", email: "priya.new@example.com" });
    render(<ChangeEmailForm />);

    submit();

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("priya.new@example.com");
    expect(notice).toHaveTextContent("link");
    expect(notice).toHaveTextContent("one hour");
    expect(notice).toHaveTextContent(/nothing changes until/i);
    expect(notice).not.toHaveTextContent(/already|exists|registered|taken/i);
  });

  it("shows the problem and keeps the address that was typed, but never the password", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "error", message: "That password was not accepted.", email: "priya.new@example.com" });
    render(<ChangeEmailForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("password was not accepted");
    expect(screen.getByLabelText("New email address")).toHaveValue("priya.new@example.com");
    expect(screen.getByLabelText("Current password")).toHaveValue("");
  });

  it("sends a signed-out person to sign in", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "signed-out" });
    render(<ChangeEmailForm />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(/sign in/i);
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/account");
  });
});
