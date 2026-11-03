import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { QrCode } from "./qr-code";

const PASS = "M1PERERA/PRIYA ABC234 SK501 2026-11-03 11D 0007.Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbA";

describe("QrCode (ADR-0071)", () => {
  it("draws the code inline as one picture the page can describe, and fetches nothing", () => {
    const { container } = render(<QrCode text={PASS} label="QR code for boarding pass 7" />);

    const picture = screen.getByRole("img", { name: "QR code for boarding pass 7" });
    expect(picture.tagName.toLowerCase()).toBe("svg");
    expect(picture).toHaveAttribute("viewBox");
    expect(container.querySelector("path")?.getAttribute("d")).toMatch(/^M\d+ \d+h\d+v1h-\d+z/);
    expect(container.querySelector("img, image, a, script")).toBeNull();   // no address for the pass to leak into
  });

  it("keeps the pass text out of the picture's markup, since the text is shown once, beside it", () => {
    const { container } = render(<QrCode text={PASS} label="QR code for boarding pass 7" />);

    expect(container.innerHTML).not.toContain("PERERA");
    expect(container.innerHTML).not.toContain("ABC234");
  });

  it("is drawn sharp at any size, and always dark on light, because a scanner cannot read a code in the dark theme's colours", () => {
    const { container } = render(<QrCode text={PASS} label="x" />);

    expect(container.querySelector("svg")).toHaveAttribute("shape-rendering", "crispEdges");
    expect(container.querySelector("path")).toHaveAttribute("fill", "#000");
    expect(container.querySelector("rect")).toHaveAttribute("fill", "#fff");   // the quiet zone is part of the code
  });
});
