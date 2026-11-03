"use client";

import { useEffect, useRef, useState } from "react";
import { CameraError, grabFrame, openBackCamera, readCode, type Frame } from "@/lib/scan";

/** About eight looks a second: plenty for a code held in front of a camera, and kind to a phone's battery. */
const LOOK_EVERY_MS = 120;

const WHY: Record<CameraError["kind"], string> = {
  refused: "The camera was refused. Allow the camera for this site in the browser's settings, then try again.",
  unsupported: "This browser or connection cannot use a camera. A secure (https) address is needed.",
  unavailable: "No camera could be opened on this device.",
};

/** Calls {@code look} after a short wait and returns a function that cancels it. Replaced in tests, so no test waits on a clock. */
function everyLookWhile(look: () => void): () => void {
  const timer = setTimeout(look, LOOK_EVERY_MS);
  return () => clearTimeout(timer);
}

type Props = {
  /** Gets the text of the first code read. The camera is already stopped when it is called. */
  onCode: (text: string) => void;
  onClose: () => void;
  openCamera?: () => Promise<MediaStream>;
  grab?: (video: HTMLVideoElement) => Frame | null;
  schedule?: (look: () => void) => () => void;
};

/**
 * Reads a boarding pass's QR code with the phone's back camera (ADR-0072). It looks at the live picture in the browser, a few times a
 * second, and hands on the text of the first code it reads, then **stops the camera**: it reads one pass at a time, so nothing watches
 * between passengers. The pictures are read in memory and thrown away; nothing is stored or sent. It decides nothing: what to do with the
 * pass is the gate panel's, by a button a person presses (ADR-0062). The camera, the picture-taking and the timer can be replaced, so it is
 * tested without a camera.
 */
export function CameraScanner(props: Props) {
  const video = useRef<HTMLVideoElement>(null);
  const [failure, setFailure] = useState<CameraError["kind"] | null>(null);
  // The latest of everything passed in, so the camera is opened once and not again each time the page redraws.
  const latest = useRef(props);
  useEffect(() => {
    latest.current = props;
  });

  useEffect(() => {
    let alive = true;
    let cancel: () => void = () => undefined;
    let stream: MediaStream | null = null;
    const canvas = { current: null as HTMLCanvasElement | null };
    const reusedCanvas = () => (canvas.current ??= document.createElement("canvas"));
    const stop = () => {
      alive = false;
      cancel();
      stream?.getTracks().forEach((track) => track.stop());
      stream = null;
    };

    (latest.current.openCamera ?? openBackCamera)()
      .then((opened) => {
        if (!alive) {
          opened.getTracks().forEach((track) => track.stop());   // the page went away while the person was deciding: do not leave it on
          return;
        }
        stream = opened;
        const element = video.current;
        if (element) {
          element.srcObject = opened;
          void element.play?.()?.catch(() => undefined);
        }
        const look = () => {
          if (!alive) return;
          const frame = element ? (latest.current.grab ?? ((v: HTMLVideoElement) => grabFrame(v, reusedCanvas)))(element) : null;
          const text = frame ? readCode(frame) : null;
          if (text) {
            stop();
            latest.current.onCode(text);
            return;
          }
          cancel = (latest.current.schedule ?? everyLookWhile)(look);
        };
        cancel = (latest.current.schedule ?? everyLookWhile)(look);
      })
      .catch((error: unknown) => {
        if (alive) setFailure(error instanceof CameraError ? error.kind : "unavailable");
      });
    return stop;
  }, []);

  return (
    <div className="max-w-md space-y-3">
      {failure ? (
        <p role="alert" className="border-l-4 border-danger bg-white px-4 py-3 text-sm text-danger">
          {WHY[failure]}
        </p>
      ) : (
        <>
          <video ref={video} aria-label="Camera view" muted playsInline className="aspect-[4/3] w-full rounded-md border border-sand bg-black object-cover" />
          <p className="text-sm text-muted">Point the camera at the QR code on the boarding pass.</p>
        </>
      )}
      <button type="button" onClick={props.onClose} className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-sand-soft">
        {failure ? "Close" : "Stop camera"}
      </button>
    </div>
  );
}
