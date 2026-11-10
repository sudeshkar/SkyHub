"use client";

import { useEffect, useId, useRef } from "react";

type Props = {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  /** "alertdialog" for a question that needs an answer, such as confirming a cancellation. */
  role?: "dialog" | "alertdialog";
};

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * A pop-up over the page. It takes focus when it opens and gives it back when it closes, keeps Tab inside itself, stops the
 * page behind from scrolling, and closes with the close button, the Escape key or a click outside it. On a phone it rises
 * from the bottom of the screen, where it is easy to reach.
 */
export function Modal({ title, onClose, children, role = "dialog" }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const titleId = useId();
  // The latest close handler, so the effect below runs once per opening rather than on every render.
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        close.current();
        return;
      }
      if (event.key !== "Tab" || !panel.current) return;
      const items = [...panel.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
      opener?.focus?.();
    };
  }, []);

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-ink/50 p-4 sm:items-center"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) close.current();
      }}
    >
      <div
        ref={panel}
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        // Inline, because the site-wide :focus-visible ring (globals.css) outranks utility classes; the panel takes focus
        // only so the keyboard starts inside it, so a ring around the whole panel would be noise.
        style={{ outline: "none" }}
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-xl bg-white p-6 shadow-[0_8px_32px_rgb(15_27_45/0.3)]"
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-lg font-semibold">
            {title}
          </h2>
          <CloseButton label="Close" onClick={() => close.current()} />
        </div>
        {children}
      </div>
    </div>
  );
}

/** A round button with a cross, for closing something; the label is what a screen reader announces. */
export function CloseButton({ label, onClick, type = "button" }: { label: string; onClick?: () => void; type?: "button" | "submit" }) {
  return (
    <button
      type={type}
      onClick={onClick}
      aria-label={label}
      title={label}
      className="flex size-9 shrink-0 items-center justify-center rounded-full border border-line bg-white text-ink hover:border-accent hover:text-accent"
    >
      <svg viewBox="0 0 20 20" className="size-4" aria-hidden="true">
        <path d="M5 5l10 10M15 5L5 15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
    </button>
  );
}
