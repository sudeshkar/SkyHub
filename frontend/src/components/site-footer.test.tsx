import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SiteFooter } from "./site-footer";

// Next.js turns an imported image into {src, width, height, ...}; the test runner only gives a path.
vi.mock("@/assets/skyhub-logo.png", () => ({
  default: { src: "/logo.png", width: 640, height: 447 },
}));

describe("SiteFooter", () => {
  it("is the page's footer landmark with the SkyHub logo", () => {
    render(<SiteFooter />);

    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByAltText("SkyHub")).toBeInTheDocument();
  });

  it("links to the real pages", () => {
    render(<SiteFooter />);
    const nav = screen.getByRole("navigation", { name: "Footer" });

    expect(within(nav).getByRole("link", { name: "Book a flight" })).toHaveAttribute("href", "/");
    expect(within(nav).getByRole("link", { name: "Manage booking" })).toHaveAttribute("href", "/manage");
  });

  it("names both Dubai airports", () => {
    render(<SiteFooter />);

    expect(screen.getByText("Dubai International")).toBeInTheDocument();
    expect(screen.getByText("Al Maktoum International")).toBeInTheDocument();
  });

  it("opens the source code in a new tab without leaking the opener", () => {
    render(<SiteFooter />);
    const link = screen.getByRole("link", { name: /source code on github/i });

    expect(link).toHaveAttribute("href", "https://github.com/sudeshkar/SkyHub");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
  });

  it("no longer carries the old disclaimer", () => {
    render(<SiteFooter />);

    expect(screen.queryByText(/portfolio project/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/not a real airline/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/local time/i)).not.toBeInTheDocument();
  });
});
