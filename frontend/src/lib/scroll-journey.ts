/** What the scroll story says. Kept to things the app actually does today. */
export const STEPS = [
  {
    id: "search",
    label: "Search",
    title: "Search any route through Dubai",
    text: "Pick where you are going. We find direct flights and one-stop connections through Dubai International and Al Maktoum.",
  },
  {
    id: "connect",
    label: "Connect",
    title: "See every connection before you book",
    text: "If a connection changes airport, DXB to DWC, you see how long the transfer takes before you choose.",
  },
  {
    id: "fly",
    label: "Fly",
    title: "Choose your seats and go",
    text: "Pick a seat for every passenger on every flight and get your booking reference straight away.",
  },
] as const;

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/**
 * How far through a pinned (sticky) section the reader has scrolled, from 0 to 1.
 * @param sectionTop the section's top edge relative to the window (getBoundingClientRect().top)
 * @param sectionHeight the section's total height
 * @param viewportHeight the window height; the section stays pinned for (height - viewport) pixels
 */
export function sectionProgress(sectionTop: number, sectionHeight: number, viewportHeight: number): number {
  const scrollable = sectionHeight - viewportHeight;
  if (scrollable <= 0) return 0;
  return clamp(-sectionTop / scrollable, 0, 1);
}

export function stepAt(progress: number, count: number = STEPS.length): number {
  return clamp(Math.floor(progress * count), 0, count - 1);
}

/** The page scroll position that shows a given step, aimed at the middle of that step's share. */
export function scrollTargetForStep(
  step: number,
  sectionTopOnPage: number,
  sectionHeight: number,
  viewportHeight: number,
  count: number = STEPS.length,
): number {
  const scrollable = Math.max(0, sectionHeight - viewportHeight);
  return sectionTopOnPage + ((step + 0.5) / count) * scrollable;
}
