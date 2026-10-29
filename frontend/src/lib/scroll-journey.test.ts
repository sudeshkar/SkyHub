import { describe, expect, it } from "vitest";
import { scrollTargetForStep, sectionProgress, stepAt } from "./scroll-journey";

// A 3200px-tall section in a 800px-tall window can scroll 2400px while pinned.
const HEIGHT = 3200;
const VIEWPORT = 800;

describe("sectionProgress", () => {
  it("is 0 until the section reaches the top of the window", () => {
    expect(sectionProgress(500, HEIGHT, VIEWPORT)).toBe(0);
    expect(sectionProgress(0, HEIGHT, VIEWPORT)).toBe(0);
  });

  it("grows linearly while the section is pinned", () => {
    expect(sectionProgress(-1200, HEIGHT, VIEWPORT)).toBe(0.5);
    expect(sectionProgress(-600, HEIGHT, VIEWPORT)).toBe(0.25);
  });

  it("stops at 1 once the section has been scrolled through", () => {
    expect(sectionProgress(-2400, HEIGHT, VIEWPORT)).toBe(1);
    expect(sectionProgress(-5000, HEIGHT, VIEWPORT)).toBe(1);
  });

  it("is 0 when the section is not taller than the window (nothing to scroll through)", () => {
    expect(sectionProgress(-100, 700, VIEWPORT)).toBe(0);
  });
});

describe("stepAt", () => {
  it.each([
    [0, 0],
    [0.32, 0],
    [0.34, 1],
    [0.66, 1],
    [0.67, 2],
    [1, 2],
  ])("progress %f is step %i", (progress, step) => {
    expect(stepAt(progress)).toBe(step);
  });

  it("never goes outside the available steps", () => {
    expect(stepAt(-1)).toBe(0);
    expect(stepAt(7)).toBe(2);
  });
});

describe("scrollTargetForStep", () => {
  it("lands in the middle of a step's share of the scroll", () => {
    // section starts 1000px down the page; step 1 spans 1/3..2/3 of 2400px => middle at 1/2
    expect(scrollTargetForStep(1, 1000, HEIGHT, VIEWPORT)).toBe(1000 + 1200);
  });

  it("maps every step back to itself", () => {
    for (let step = 0; step < 3; step++) {
      const target = scrollTargetForStep(step, 0, HEIGHT, VIEWPORT);
      expect(stepAt(sectionProgress(-target, HEIGHT, VIEWPORT))).toBe(step);
    }
  });
});
