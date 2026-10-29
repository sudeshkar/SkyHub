import type { Segment } from "./api/booking";

type RawParams = Record<string, string | string[] | undefined>;

export type BookingSelection = { segments: Segment[]; passengers: number };

export type ParsedBooking = { ok: true; selection: BookingSelection } | { ok: false };

const SEGMENT = /^([A-Z0-9]{2}[0-9]{1,4})_(\d{4}-\d{2}-\d{2})$/;
const MAX_SEGMENTS = 4;
export const MAX_PASSENGERS = 9;

function single(value: string | string[] | undefined): string {
  return (Array.isArray(value) ? value[0] : value)?.trim() ?? "";
}

/** "SK501_2026-11-02,SK901_2026-11-02" — the flights chosen on the results page. */
export function encodeSegments(segments: Segment[]): string {
  return segments.map((s) => `${s.flightNumber}_${s.departureDate}`).join(",");
}

export function parsePassengers(value: string | string[] | undefined): number {
  const count = Number(single(value) || "1");
  return Number.isInteger(count) && count >= 1 && count <= MAX_PASSENGERS ? count : 1;
}

export function parseBooking(params: RawParams): ParsedBooking {
  const raw = single(params.legs).toUpperCase();
  const parts = raw ? raw.split(",") : [];
  if (parts.length === 0 || parts.length > MAX_SEGMENTS) return { ok: false };

  const segments: Segment[] = [];
  for (const part of parts) {
    const match = SEGMENT.exec(part);
    if (!match || Number.isNaN(Date.parse(match[2]))) return { ok: false };
    segments.push({ flightNumber: match[1], departureDate: match[2] });
  }
  return { ok: true, selection: { segments, passengers: parsePassengers(params.pax) } };
}
