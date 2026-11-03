import { qrCode } from "@/lib/qr";

/** A picture as a camera or a canvas gives it: RGBA pixels, row by row. */
export type TestFrame = { data: Uint8ClampedArray; width: number; height: number };

/** A white frame of this size, as a camera sees an empty scene. */
export function blankFrame(width = 320, height = 240): TestFrame {
  return { data: new Uint8ClampedArray(width * height * 4).fill(255), width, height };
}

/**
 * Draws the path of a code the way a browser would, one pixel per module scaled up, so a reader can be pointed at what is really drawn and
 * not at the matrix it came from. Understands only the shape the encoder writes: {@code M x y h w v 1 h -w z}.
 */
export function drawPath(path: string, size: number, scale = 6): TestFrame {
  const width = size * scale;
  const frame = blankFrame(width, width);
  for (const run of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-\d+z/g)) {
    const [x, y, w] = [Number(run[1]), Number(run[2]), Number(run[3])];
    for (let py = y * scale; py < (y + 1) * scale; py++) {
      for (let px = x * scale; px < (x + w) * scale; px++) {
        const at = (py * width + px) * 4;
        frame.data[at] = frame.data[at + 1] = frame.data[at + 2] = 0;   // black
      }
    }
  }
  return frame;
}

/** What a camera pointed at the QR code of this text would see. */
export function frameOf(text: string, scale = 6): TestFrame {
  const { size, path } = qrCode(text);
  return drawPath(path, size, scale);
}
