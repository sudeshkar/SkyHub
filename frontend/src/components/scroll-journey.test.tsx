import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { STEPS } from "@/lib/scroll-journey";
import { ScrollJourney } from "./scroll-journey";

// Next.js turns an imported image into {src, width, height, blurDataURL}; the test runner only gives a path.
vi.mock("@/assets/scroll-aircraft.jpg", () => ({
  default: { src: "/aircraft.jpg", width: 1920, height: 1080, blurDataURL: "data:image/jpeg;base64,AAAA" },
}));

function stubWindow({ reducedMotion }: { reducedMotion: boolean }) {
  vi.stubGlobal(
    "matchMedia",
    (query: string) => ({ matches: reducedMotion && query.includes("reduce"), addEventListener() {}, removeEventListener() {} }),
  );
  // jsdom has no layout, so give the section a believable position: 1/2 of the way through a pinned scroll.
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
    top: -1200, height: 3200, left: 0, width: 1000, bottom: 2000, right: 1000, x: 0, y: -1200, toJSON() {},
  });
  Object.defineProperty(window, "innerHeight", { value: 800, configurable: true });
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    callback(0);
    return 1;
  });
}

describe("ScrollJourney", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
  });

  it("describes every step for readers and screen readers", () => {
    stubWindow({ reducedMotion: false });
    render(<ScrollJourney />);

    for (const step of STEPS) {
      expect(screen.getAllByText(step.title).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole("heading", { name: "How SkyHub works" })).toBeInTheDocument();
  });

  it("marks the step that matches the scroll position as current", () => {
    stubWindow({ reducedMotion: false });
    render(<ScrollJourney />);

    // halfway through the pinned scroll is the middle step
    expect(screen.getByRole("button", { name: "Step 2 of 3: Connect" })).toHaveAttribute("aria-current", "step");
    expect(screen.getByRole("button", { name: "Step 1 of 3: Search" })).not.toHaveAttribute("aria-current");
  });

  it("scrolls to a step when its marker is clicked", () => {
    stubWindow({ reducedMotion: false });
    vi.spyOn(window, "scrollY", "get").mockReturnValue(1200);
    render(<ScrollJourney />);

    fireEvent.click(screen.getByRole("button", { name: "Step 3 of 3: Fly" }));

    // section top on page = -1200 + 1200 = 0; scrollable = 2400; step 3 is the middle of the last third
    expect(window.scrollTo).toHaveBeenCalledWith({ top: (2.5 / 3) * 2400, behavior: "smooth" });
  });

  it("does not animate for people who prefer reduced motion", () => {
    stubWindow({ reducedMotion: true });
    const { container } = render(<ScrollJourney />);

    expect(container.querySelector("section")?.style.getPropertyValue("--p")).toBe("");
  });
});
