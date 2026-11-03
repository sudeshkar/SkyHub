import jsQR from "jsqr";
import { describe, expect, it } from "vitest";
import { qrCode } from "./qr";

/** What a boarding pass says: the signed text of ADR-0013 (the payload, a dot, and the base64url signature). */
const PASS = "M1PERERA/PRIYA ABC234 SK501 2026-11-03 11D 0007.Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbA";

/**
 * Draws the path the way a browser would, one pixel per module scaled up, so an independent reader (jsQR) can be pointed at what is
 * really drawn and not at the matrix it came from. Understands only the shape the encoder writes: {@code M x y h w v 1 h -w z}.
 */
function render(path: string, size: number, scale = 6): { data: Uint8ClampedArray; width: number; height: number } {
  const width = size * scale;
  const data = new Uint8ClampedArray(width * width * 4).fill(255);   // opaque white
  for (const run of path.matchAll(/M(\d+) (\d+)h(\d+)v1h-\d+z/g)) {
    const [x, y, w] = [Number(run[1]), Number(run[2]), Number(run[3])];
    for (let py = y * scale; py < (y + 1) * scale; py++) {
      for (let px = x * scale; px < (x + w) * scale; px++) {
        const at = (py * width + px) * 4;
        data[at] = data[at + 1] = data[at + 2] = 0;   // black
      }
    }
  }
  return { data, width, height: width };
}

describe("qrCode (ADR-0071)", () => {
  it("draws a code that an independent reader decodes to exactly the pass text", () => {
    const { size, path } = qrCode(PASS);
    const image = render(path, size);

    expect(jsQR(image.data, image.width, image.height)?.data).toBe(PASS);
  });

  it("holds the longest pass the gate accepts (300 characters)", () => {
    const longest = PASS.slice(0, 7) + "X".repeat(300 - 7);
    const { size, path } = qrCode(longest);
    const image = render(path, size, 4);

    expect(longest).toHaveLength(300);
    expect(jsQR(image.data, image.width, image.height)?.data).toBe(longest);
  });

  it("keeps a quiet zone of four modules round the code, which a scanner needs to find it", () => {
    const { size, path } = qrCode(PASS);
    const starts = [...path.matchAll(/M(\d+) (\d+)h(\d+)v1h-\d+z/g)].map((m) => ({ x: Number(m[1]), y: Number(m[2]), w: Number(m[3]) }));

    expect(Math.min(...starts.map((s) => s.x))).toBeGreaterThanOrEqual(4);
    expect(Math.min(...starts.map((s) => s.y))).toBeGreaterThanOrEqual(4);
    expect(Math.max(...starts.map((s) => s.x + s.w))).toBeLessThanOrEqual(size - 4);
    expect(Math.max(...starts.map((s) => s.y))).toBeLessThanOrEqual(size - 5);
  });

  it("draws the same code for the same pass and a different one for a different pass", () => {
    expect(qrCode(PASS)).toEqual(qrCode(PASS));
    expect(qrCode(PASS.replace("11D", "11E")).path).not.toBe(qrCode(PASS).path);
  });

  it("draws one run per stretch of dark modules in a row, not one box per module", () => {
    const { path } = qrCode(PASS);
    const runs = [...path.matchAll(/M\d+ \d+h(\d+)v1/g)];

    expect(runs.length).toBeGreaterThan(0);
    expect(runs.some((run) => Number(run[1]) > 1)).toBe(true);   // neighbours are joined
  });

  it("refuses to draw nothing", () => {
    expect(() => qrCode("")).toThrow();
  });
});
