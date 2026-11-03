import { describe, expect, it, vi } from "vitest";
import { blankFrame, frameOf } from "@/test/qr-frame";
import { CameraError, grabFrame, openBackCamera, readCode } from "./scan";

const PASS = "M1PERERA/PRIYA ABC234 SK501 2026-11-03 11D 0007.Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbA";

describe("readCode (ADR-0072)", () => {
  it("reads the pass text from a picture of its QR code", () => {
    expect(readCode(frameOf(PASS))).toBe(PASS);
  });

  it("finds the code in a picture where it is small and surrounded by empty space, as a hand-held phone sees it", () => {
    const code = frameOf(PASS, 3);
    const frame = blankFrame(640, 480);
    for (let y = 0; y < code.height; y++) {
      frame.data.set(code.data.subarray(y * code.width * 4, (y + 1) * code.width * 4), ((y + 120) * frame.width + 200) * 4);
    }

    expect(readCode(frame)).toBe(PASS);
  });

  it("reads nothing from an empty scene", () => {
    expect(readCode(blankFrame())).toBeNull();
  });

  it("does not hand on text too long to be a boarding pass, which the gate would only refuse", () => {
    expect(readCode(frameOf("X".repeat(301), 4))).toBeNull();
  });
});

describe("openBackCamera (ADR-0072)", () => {
  const stream = { getTracks: () => [] } as unknown as MediaStream;

  it("asks for the back camera, with no sound", async () => {
    const getUserMedia = vi.fn().mockResolvedValue(stream);

    await expect(openBackCamera({ getUserMedia } as unknown as MediaDevices)).resolves.toBe(stream);

    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: { ideal: "environment" } }, audio: false });
  });

  it("says the browser cannot use a camera when it has none to offer, as on a page that is not secure", async () => {
    await expect(openBackCamera(undefined)).rejects.toMatchObject({ kind: "unsupported" });
  });

  it("says the camera was refused when the person, or the browser's settings, said no", async () => {
    for (const name of ["NotAllowedError", "SecurityError"]) {
      const getUserMedia = vi.fn().mockRejectedValue(Object.assign(new Error("no"), { name }));
      await expect(openBackCamera({ getUserMedia } as unknown as MediaDevices)).rejects.toMatchObject({ kind: "refused" });
    }
  });

  it("says there is no usable camera for any other failure", async () => {
    const getUserMedia = vi.fn().mockRejectedValue(Object.assign(new Error("none"), { name: "NotFoundError" }));

    const failure = await openBackCamera({ getUserMedia } as unknown as MediaDevices).catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(CameraError);
    expect(failure).toMatchObject({ kind: "unavailable" });
  });
});

describe("grabFrame (ADR-0072)", () => {
  function fakeCanvas() {
    const drawn: number[][] = [];
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({
        drawImage: (_video: unknown, ...args: number[]) => drawn.push(args),
        getImageData: (_x: number, _y: number, w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4), width: w, height: h }),
      }),
    };
    return { canvas, drawn };
  }
  const video = (width: number, height: number, readyState = 4) => ({ videoWidth: width, videoHeight: height, readyState }) as HTMLVideoElement;

  it("takes nothing until the camera has a picture", () => {
    const { canvas } = fakeCanvas();

    expect(grabFrame(video(0, 0, 0), () => canvas as unknown as HTMLCanvasElement)).toBeNull();
    expect(grabFrame(video(1280, 720, 1), () => canvas as unknown as HTMLCanvasElement)).toBeNull();
  });

  it("shrinks a large picture to at most 640 across, so reading stays quick on a phone, keeping its shape", () => {
    const { canvas, drawn } = fakeCanvas();

    const frame = grabFrame(video(1280, 720), () => canvas as unknown as HTMLCanvasElement);

    expect(frame).toMatchObject({ width: 640, height: 360 });
    expect(drawn[0]).toEqual([0, 0, 640, 360]);
  });

  it("leaves a small picture as it is", () => {
    const { canvas } = fakeCanvas();

    expect(grabFrame(video(320, 240), () => canvas as unknown as HTMLCanvasElement)).toMatchObject({ width: 320, height: 240 });
  });

  it("takes nothing when the browser will not give a drawing surface", () => {
    expect(grabFrame(video(640, 480), () => ({ getContext: () => null }) as unknown as HTMLCanvasElement)).toBeNull();
  });
});
