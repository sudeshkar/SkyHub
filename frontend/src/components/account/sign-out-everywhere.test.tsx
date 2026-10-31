import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SignOutEverywhere } from "./sign-out-everywhere";

vi.mock("@/app/account/security-actions", () => ({ signOutEverywhere: vi.fn() }));

describe("SignOutEverywhere", () => {
  it("offers a button that says what it does", () => {
    render(<SignOutEverywhere />);

    expect(screen.getByRole("button", { name: "Sign out everywhere" })).toBeInTheDocument();
  });

  it("explains that every device is signed out, this one included, and that the password is not changed", () => {
    render(<SignOutEverywhere />);

    expect(screen.getByText(/every device/i)).toBeInTheDocument();
    expect(screen.getByText(/including this one/i)).toBeInTheDocument();
    expect(screen.getByText(/password is not changed/i)).toBeInTheDocument();
  });

  it("is a form post, so nothing about it appears in an address", () => {
    const { container } = render(<SignOutEverywhere />);

    expect(container.querySelector("form")).not.toBeNull();
    expect(container.querySelector("a")).toBeNull();
  });
});
