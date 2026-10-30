import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LinkForm } from "./link-form";
import { requestLink } from "./actions";

vi.mock("./actions", () => ({ requestLink: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
});

describe("LinkForm", () => {
  it("says the same thing whether the address is new or already has an account", async () => {
    vi.mocked(requestLink).mockResolvedValue({ status: "sent", email: "priya@example.com" });
    render(<LinkForm mode="sign-up" />);

    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "priya@example.com" } });
    fireEvent.submit(screen.getByRole("button", { name: "Email me a link" }).closest("form")!);

    const notice = await screen.findByRole("status");
    expect(notice).toHaveTextContent("priya@example.com");
    expect(notice).toHaveTextContent("link");
    expect(notice).toHaveTextContent("one hour");
    expect(notice).not.toHaveTextContent(/already|exists|registered/i);
  });

  it("uses different words for resetting a password", () => {
    render(<LinkForm mode="reset" />);

    expect(screen.getByRole("heading", { name: "Forgot your password?" })).toBeInTheDocument();
  });

  it("shows the problem and keeps what was typed", async () => {
    vi.mocked(requestLink).mockResolvedValue({ status: "error", message: "Enter a valid email address.", email: "nope" });
    render(<LinkForm mode="sign-up" />);

    fireEvent.submit(screen.getByRole("button", { name: "Email me a link" }).closest("form")!);

    expect(await screen.findByRole("alert")).toHaveTextContent("valid email");
    expect(screen.getByLabelText("Email address")).toHaveValue("nope");
  });
});
