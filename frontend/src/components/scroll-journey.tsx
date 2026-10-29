"use client";

import Image from "next/image";
import { useEffect, useRef, useState, type CSSProperties, type PointerEvent } from "react";
import aircraft from "@/assets/scroll-aircraft.jpg";
import { scrollTargetForStep, sectionProgress, STEPS, stepAt } from "@/lib/scroll-journey";

/**
 * A pinned scene that plays as you scroll: the camera pushes in toward the aircraft while three captions
 * step through. Progress lives in a CSS variable (--p) written straight to the element, so scrolling never
 * re-renders React; only the active step is state. Visitors who prefer reduced motion get the same content
 * as a still picture with the three steps listed beneath (the layout switch is pure CSS: motion-safe:).
 */
export function ScrollJourney() {
  const sectionRef = useRef<HTMLElement>(null);
  const [step, setStep] = useState(0);

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let frame = 0;

    const update = () => {
      frame = 0;
      if (reducedMotion.matches) return;
      const { top, height } = section.getBoundingClientRect();
      const progress = sectionProgress(top, height, window.innerHeight);
      section.style.setProperty("--p", progress.toFixed(4));
      setStep(stepAt(progress));
    };
    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };

    schedule();
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    return () => {
      window.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  /** A gentle parallax that follows a mouse; touch and pen input are ignored. */
  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    if (event.pointerType !== "mouse") return;
    const section = sectionRef.current;
    if (!section) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    section.style.setProperty("--mx", (((event.clientX - bounds.left) / bounds.width) * 2 - 1).toFixed(3));
    section.style.setProperty("--my", (((event.clientY - bounds.top) / bounds.height) * 2 - 1).toFixed(3));
  }

  function goToStep(index: number) {
    const section = sectionRef.current;
    if (!section) return;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const { top: topInWindow, height } = section.getBoundingClientRect();
    const sectionTopOnPage = topInWindow + window.scrollY;
    const top = reduced
      ? sectionTopOnPage
      : scrollTargetForStep(index, sectionTopOnPage, height, window.innerHeight);
    window.scrollTo({ top, behavior: reduced ? "auto" : "smooth" });
  }

  const pushIn: CSSProperties = {
    // Camera push-in toward the aircraft's nose, plus the mouse parallax.
    transform:
      "translate3d(calc(var(--mx, 0) * -14px), calc(var(--my, 0) * -8px), 0) scale(calc(1 + var(--p, 0) * 0.55))",
    transformOrigin: "50% 62%",
  };

  return (
    <section
      ref={sectionRef}
      aria-labelledby="journey-heading"
      className="relative bg-ink text-ivory motion-safe:h-[320svh]"
    >
      <h2 id="journey-heading" className="sr-only">
        How SkyHub works
      </h2>

      <div className="motion-safe:sticky motion-safe:top-0 motion-safe:h-svh">
        <div
          onPointerMove={onPointerMove}
          className="relative aspect-video w-full overflow-hidden motion-safe:aspect-auto motion-safe:h-full"
        >
          <div className="absolute inset-0 will-change-transform" style={pushIn}>
            <Image
              src={aircraft}
              alt="An airliner on the apron at sunset, seen from the front, with the sun glinting on the wet tarmac."
              fill
              sizes="100vw"
              placeholder="blur"
              className="object-cover"
            />
          </div>

          {/* Darkens as you go in, so the captions stay readable. */}
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-0 bg-gradient-to-t from-ink via-ink/40 to-ink/10"
            style={{ opacity: "calc(0.45 + var(--p, 0) * 0.5)" }}
          />

          {/* Captions: stacked on top of each other and cross-faded while scrolling. */}
          <div className="absolute inset-x-0 bottom-0 hidden px-4 pb-24 motion-safe:block sm:pb-28">
            <div className="relative mx-auto h-44 max-w-5xl sm:h-40">
              {STEPS.map((item, index) => (
                <div
                  key={item.id}
                  aria-hidden={index === step ? undefined : true}
                  // The old caption leaves quickly (200 ms) and the new one arrives after it (200 ms delay), so
                  // two captions are never readable on top of each other.
                  className={`absolute inset-x-0 bottom-0 max-w-xl transition-all ease-out ${
                    index === step
                      ? "translate-y-0 opacity-100 delay-200 duration-500"
                      : "pointer-events-none translate-y-4 opacity-0 duration-200"
                  }`}
                >
                  <p className="font-mono text-sm tracking-widest text-sand">
                    {String(index + 1).padStart(2, "0")} / {String(STEPS.length).padStart(2, "0")}
                  </p>
                  <p className="mt-2 font-display text-3xl leading-tight sm:text-4xl">{item.title}</p>
                  <p className="mt-3 text-base text-sand sm:text-lg">{item.text}</p>
                </div>
              ))}
            </div>
          </div>

          {/* Step markers and progress: click one to scroll to that moment. */}
          <nav
            aria-label="Journey steps"
            className="absolute inset-x-0 bottom-0 hidden px-4 pb-6 motion-safe:block"
          >
            <div className="mx-auto max-w-5xl">
              <div className="h-0.5 w-full bg-ivory/20" aria-hidden="true">
                <div className="h-full bg-copper" style={{ width: "calc(var(--p, 0) * 100%)" }} />
              </div>
              <ol className="mt-3 flex gap-6">
                {STEPS.map((item, index) => (
                  <li key={item.id}>
                    <button
                      type="button"
                      onClick={() => goToStep(index)}
                      aria-current={index === step ? "step" : undefined}
                      aria-label={`Step ${index + 1} of ${STEPS.length}: ${item.label}`}
                      className={`text-sm font-medium tracking-wide transition-colors ${
                        index === step ? "text-ivory" : "text-sand/60 hover:text-ivory"
                      }`}
                    >
                      {item.label}
                    </button>
                  </li>
                ))}
              </ol>
            </div>
          </nav>

          <p
            aria-hidden="true"
            className="pointer-events-none absolute right-4 top-6 hidden text-xs tracking-widest text-ivory/80 transition-opacity duration-300 motion-safe:block"
            style={{ opacity: "calc(1 - var(--p, 0) * 12)" }}
          >
            SCROLL ↓
          </p>
        </div>

        {/* For reduced motion: the same three steps, plainly listed under the picture. */}
        <ol className="mx-auto grid max-w-5xl gap-8 px-4 py-10 sm:grid-cols-3 motion-safe:hidden">
          {STEPS.map((item, index) => (
            <li key={item.id}>
              <p className="font-mono text-sm tracking-widest text-sand">{String(index + 1).padStart(2, "0")}</p>
              <p className="mt-2 font-display text-2xl leading-tight">{item.title}</p>
              <p className="mt-2 text-sand">{item.text}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
