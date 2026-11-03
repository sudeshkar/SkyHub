import { encode } from "uqr";

/** The empty margin round a code, in modules, that a scanner needs to find where the code starts (the QR standard asks for four). */
const QUIET_ZONE = 4;

/** A drawing of a code: {@code size} modules a side, margin included, and one path that draws every dark module. */
export type QrCode = { size: number; path: string };

/**
 * Draws text as a QR code (ADR-0071): the error correction is level M, which lets a scratched or partly covered code still read, and the
 * dark modules come out as one run per stretch in a row (<code>M x y h w v 1 h -w z</code>), so the path is short and the picture is
 * sharp at any size. Pure and deterministic: the same text always gives the same drawing, with no network and no clock. Cost is linear in
 * the number of modules (at most 177 by 177 for the largest code).
 */
export function qrCode(text: string): QrCode {
  if (text.length === 0) {
    throw new Error("There is nothing to draw a code of");
  }
  const { data } = encode(text, { ecc: "M", border: 0 });
  const runs: string[] = [];
  data.forEach((row, y) => {
    for (let x = 0; x < row.length; ) {
      if (!row[x]) {
        x++;
        continue;
      }
      let end = x;
      while (end < row.length && row[end]) end++;
      runs.push(`M${x + QUIET_ZONE} ${y + QUIET_ZONE}h${end - x}v1h-${end - x}z`);
      x = end;
    }
  });
  return { size: data.length + 2 * QUIET_ZONE, path: runs.join("") };
}
