"use client";

import { useEffect, useRef, useState } from "react";

function secondsLeft(expiresAt: string): number {
  return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - Date.now()) / 1000));
}

/** Counts down to the end of a seat hold and tells the page, once, when it is over. */
export function HoldCountdown({ expiresAt, onExpire }: { expiresAt: string; onExpire: () => void }) {
  const [left, setLeft] = useState(() => secondsLeft(expiresAt));
  const expire = useRef(onExpire);
  const fired = useRef(false);

  useEffect(() => {
    expire.current = onExpire;
  });

  useEffect(() => {
    fired.current = false;
    function tick() {
      const remaining = secondsLeft(expiresAt);
      setLeft(remaining);
      if (remaining === 0 && !fired.current) {
        fired.current = true;
        expire.current();
      }
    }
    tick();
    const timer = setInterval(tick, 1_000);
    return () => clearInterval(timer);
  }, [expiresAt]);

  const text = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`;
  return (
    <p className="mb-6 text-sm text-muted">
      We are holding your seats for <span className="font-mono font-semibold text-ink">{text}</span>
      {/* Announced only at the end, so a screen reader is not interrupted every second. */}
      <span role="status" className="sr-only">
        {left === 0 ? "Your seat hold has ended." : ""}
      </span>
    </p>
  );
}
