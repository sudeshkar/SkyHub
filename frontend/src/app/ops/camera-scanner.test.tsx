import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CameraError } from "@/lib/scan";
import { blankFrame, frameOf, type TestFrame } from "@/test/qr-frame";
import { CameraScanner } from "./camera-scanner";

const PASS = "M1PERERA/PRIYA ABC234 SK501 2026-11-03 11D 0007.Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbA";

/** A camera whose frames are scripted, and whose tracks record being stopped. */
function fakeCamera(frames: TestFrame[]) {
  const stop = vi.fn();
  const stream = { getTracks: () => [{ stop }] } as unknown as MediaStream;
  let next = 0;
  return {
    stop,
    openCamera: vi.fn().mockResolvedValue(stream),
    grab: vi.fn(() => frames[Math.min(next++, frames.length - 1)] ?? null),
  };
}

/** Frames come one at a time when a test says so, instead of on a timer. */
function manualClock() {
  let pending: (() => void) | null = null;
  return {
    schedule: (callback: () => void) => {
      pending = callback;
      return () => {
        pending = null;
      };
    },
    tick: () => {
      const run = pending;
      pending = null;
      run?.();
    },
    isWaiting: () => pending !== null,
  };
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
  Object.defineProperty(HTMLMediaElement.prototype, "play", { configurable: true, value: vi.fn().mockResolvedValue(undefined) });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("CameraScanner (ADR-0072)", () => {
  it("opens the camera and shows what it sees, saying what to do", async () => {
    const camera = fakeCamera([blankFrame()]);
    render(<CameraScanner onCode={vi.fn()} onClose={vi.fn()} {...camera} schedule={manualClock().schedule} />);

    await waitFor(() => expect(camera.openCamera).toHaveBeenCalledTimes(1));

    expect(screen.getByLabelText("Camera view")).toBeInTheDocument();
    expect(screen.getByText(/point the camera at the qr code/i)).toBeInTheDocument();
  });

  it("keeps looking while the picture holds no code, then hands on the text it reads and stops the camera", async () => {
    const camera = fakeCamera([blankFrame(), blankFrame(), frameOf(PASS)]);
    const clock = manualClock();
    const onCode = vi.fn();
    render(<CameraScanner onCode={onCode} onClose={vi.fn()} {...camera} schedule={clock.schedule} />);
    await waitFor(() => expect(camera.openCamera).toHaveBeenCalled());

    await act(async () => clock.tick());
    await act(async () => clock.tick());
    expect(onCode).not.toHaveBeenCalled();
    await act(async () => clock.tick());

    expect(onCode).toHaveBeenCalledTimes(1);
    expect(onCode).toHaveBeenCalledWith(PASS);
    expect(camera.stop).toHaveBeenCalled();   // nothing is watching once the pass is read
    expect(clock.isWaiting()).toBe(false);     // and nothing is looking for another
  });

  it("does not look at another picture once it has read one", async () => {
    const camera = fakeCamera([frameOf(PASS)]);
    const clock = manualClock();
    render(<CameraScanner onCode={vi.fn()} onClose={vi.fn()} {...camera} schedule={clock.schedule} />);
    await waitFor(() => expect(camera.openCamera).toHaveBeenCalled());

    await act(async () => clock.tick());
    await act(async () => clock.tick());

    expect(camera.grab).toHaveBeenCalledTimes(1);
  });

  it("sends the pictures nowhere: they are looked at in the browser and thrown away", async () => {
    const camera = fakeCamera([frameOf(PASS)]);
    const clock = manualClock();
    render(<CameraScanner onCode={vi.fn()} onClose={vi.fn()} {...camera} schedule={clock.schedule} />);
    await waitFor(() => expect(camera.openCamera).toHaveBeenCalled());

    await act(async () => clock.tick());

    expect(fetch).not.toHaveBeenCalled();
  });

  it("stops the camera when it is closed, and when the page goes away", async () => {
    const camera = fakeCamera([blankFrame()]);
    const onClose = vi.fn();
    const { unmount } = render(<CameraScanner onCode={vi.fn()} onClose={onClose} {...camera} schedule={manualClock().schedule} />);
    await waitFor(() => expect(camera.openCamera).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Stop camera" }));
    expect(onClose).toHaveBeenCalled();

    unmount();
    expect(camera.stop).toHaveBeenCalled();
  });

  it("stops a camera that opened after the page had already gone away", async () => {
    const camera = fakeCamera([blankFrame()]);
    let release: (stream: MediaStream) => void = () => undefined;
    camera.openCamera.mockReturnValue(new Promise<MediaStream>((resolve) => (release = resolve)));
    const { unmount } = render(<CameraScanner onCode={vi.fn()} onClose={vi.fn()} {...camera} schedule={manualClock().schedule} />);

    unmount();
    await act(async () => release({ getTracks: () => [{ stop: camera.stop }] } as unknown as MediaStream));

    expect(camera.stop).toHaveBeenCalled();   // not left running with nobody to see it
  });

  it.each([
    ["refused", /camera was refused|allow the camera/i],
    ["unsupported", /secure|cannot use a camera/i],
    ["unavailable", /no camera|could not be opened/i],
  ] as const)("says in plain words why the camera could not be used (%s), and offers to close", async (kind, words) => {
    const camera = fakeCamera([]);
    camera.openCamera.mockRejectedValue(new CameraError(kind));
    render(<CameraScanner onCode={vi.fn()} onClose={vi.fn()} {...camera} schedule={manualClock().schedule} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
  });
});
