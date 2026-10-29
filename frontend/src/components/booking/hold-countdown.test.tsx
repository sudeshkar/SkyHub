import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HoldCountdown } from "./hold-countdown";

describe("HoldCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-29T10:00:00Z"));
  });
  afterEach(() => vi.useRealTimers());

  it("shows the time left as minutes and seconds", () => {
    render(<HoldCountdown expiresAt="2026-10-29T10:09:05Z" onExpire={vi.fn()} />);

    expect(screen.getByText("9:05")).toBeInTheDocument();
  });

  it("counts down every second", () => {
    render(<HoldCountdown expiresAt="2026-10-29T10:01:00Z" onExpire={vi.fn()} />);

    act(() => {
      vi.advanceTimersByTime(3_000);
    });

    expect(screen.getByText("0:57")).toBeInTheDocument();
  });

  it("tells the page once when the time is up", () => {
    const onExpire = vi.fn();
    render(<HoldCountdown expiresAt="2026-10-29T10:00:02Z" onExpire={onExpire} />);

    act(() => {
      vi.advanceTimersByTime(5_000);
    });

    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it("expires straight away when the deadline has already passed", () => {
    const onExpire = vi.fn();
    render(<HoldCountdown expiresAt="2026-10-29T09:59:00Z" onExpire={onExpire} />);

    expect(onExpire).toHaveBeenCalledTimes(1);
  });
});
