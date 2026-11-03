import jsQR from "jsqr";

/** A picture as a canvas gives it: RGBA pixels, row by row. */
export type Frame = { data: Uint8ClampedArray; width: number; height: number };

/** The longest text a boarding pass has (the gate refuses anything longer, ADR-0056), so a longer code is not one. */
const MAX_PASS = 300;

/** Pictures wider than this are shrunk before they are read: a code still reads, and a phone does the work several times a second. */
const MAX_WIDTH = 640;

/**
 * Reads the text of a QR code from a picture, or nothing (ADR-0072). Pure: it looks at the pixels it is given and keeps nothing. It does
 * not try colour-inverted codes, which doubles the work and which the pass never is (ADR-0071 draws it black on white).
 */
export function readCode(frame: Frame): string | null {
  const found = jsQR(frame.data, frame.width, frame.height, { inversionAttempts: "dontInvert" });
  const text = found?.data ?? "";
  return text.length > 0 && text.length <= MAX_PASS ? text : null;
}

/** Why a camera could not be used, in terms a person can act on: allow it, use a secure address, or use another device. */
export class CameraError extends Error {
  constructor(readonly kind: "unsupported" | "refused" | "unavailable") {
    super(`Camera ${kind}`);
  }
}

/**
 * Opens the back camera, without sound. The browser asks the person for permission, and only a secure page (https, or localhost) is
 * offered a camera at all; both show up here as a {@link CameraError}.
 */
export async function openBackCamera(
  devices: Pick<MediaDevices, "getUserMedia"> | undefined = typeof navigator === "undefined" ? undefined : navigator.mediaDevices,
): Promise<MediaStream> {
  if (!devices?.getUserMedia) {
    throw new CameraError("unsupported");
  }
  try {
    return await devices.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false });
  } catch (failure) {
    const name = failure instanceof Error ? failure.name : "";
    throw new CameraError(name === "NotAllowedError" || name === "SecurityError" ? "refused" : "unavailable");
  }
}

/**
 * Takes one picture from a playing video, shrunk to at most 640 across and keeping its shape, or nothing when the camera has no picture
 * yet or the browser will not give a drawing surface. The picture lives only in memory: it is read and dropped, never stored or sent.
 */
export function grabFrame(video: HTMLVideoElement, makeCanvas: () => HTMLCanvasElement = () => document.createElement("canvas")): Frame | null {
  if (video.readyState < 2 || !video.videoWidth || !video.videoHeight) {
    return null;
  }
  const scale = Math.min(1, MAX_WIDTH / video.videoWidth);
  const width = Math.round(video.videoWidth * scale);
  const height = Math.round(video.videoHeight * scale);
  const canvas = makeCanvas();
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) {
    return null;
  }
  context.drawImage(video, 0, 0, width, height);
  const picture = context.getImageData(0, 0, width, height);
  return { data: picture.data, width: picture.width, height: picture.height };
}
