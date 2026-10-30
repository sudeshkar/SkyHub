import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SetPasswordForm } from "./set-password-form";
import { setPassword } from "./actions";

vi.mock("./actions", () => ({ setPassword: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

function submit() {
  fireEvent.submit(screen.getByRole("button", { name: "Save password" }).closest("form")!);
}

describe("SetPasswordForm", () => {
  it("carries the emailed token in a hidden field, never shown", () => {
    const { container } = render(<SetPasswordForm token="abc123" />);

    expect(container.querySelector('input[name="token"]')).toHaveValue("abc123");
    expect(container.querySelector('input[name="token"]')).toHaveAttribute("type", "hidden");
  });

  it("asks twice for the password and says how long it must be", () => {
    render(<SetPasswordForm token="abc123" />);

    expect(screen.getByLabelText(/New password/)).toHaveAttribute("minLength", "12");
    expect(screen.getByLabelText("Repeat the password")).toBeInTheDocument();
  });

  it("points to sign-in once the password is saved", async () => {
    vi.mocked(setPassword).mockResolvedValue({ status: "done" });
    render(<SetPasswordForm token="abc123" />);

    submit();

    expect(await screen.findByRole("status")).toHaveTextContent("password is saved");
    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/account");
  });

  it("offers a new link when this one has stopped working", async () => {
    vi.mocked(setPassword).mockResolvedValue({ status: "link-expired" });
    render(<SetPasswordForm token="abc123" />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("no longer works");
    expect(screen.getByRole("link", { name: "Ask for a new link" })).toHaveAttribute("href", "/account/forgot");
  });

  it("shows why a password was not accepted", async () => {
    vi.mocked(setPassword).mockResolvedValue({ status: "error", message: "The two passwords do not match." });
    render(<SetPasswordForm token="abc123" />);

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent("do not match");
  });
});
