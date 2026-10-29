import type { components } from "./booking-contract";

export type SeatMap = components["schemas"]["SeatMap"];
export type Seat = components["schemas"]["Seat"];
export type Segment = components["schemas"]["Segment"];
export type Pnr = components["schemas"]["Pnr"];
export type CreatePnrRequest = components["schemas"]["CreatePnrRequest"];
type Problem = components["schemas"]["Problem"];

export type BookingOutcome =
  | { kind: "booked"; pnr: Pnr }
  | { kind: "seat-taken"; detail: string }
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

// Server-side only: the browser never calls the Java service directly.
const API_URL = process.env.SKYHUB_BOOKING_API_URL ?? "http://localhost:8082";
const TIMEOUT_MS = 5_000;

async function call(path: string, init?: RequestInit): Promise<Response | null> {
  try {
    return await fetch(new URL(path, API_URL), { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch {
    return null;
  }
}

async function problemDetail(response: Response, fallback: string): Promise<string> {
  const problem = (await response.json().catch(() => null)) as Problem | null;
  return problem?.detail ?? fallback;
}

export async function getSeatMap(segment: Segment): Promise<SeatMap | "not-found" | "unavailable"> {
  const response = await call(
    `/api/v1/flights/${encodeURIComponent(segment.flightNumber)}/departures/${segment.departureDate}/seats`,
  );
  if (response?.ok) return (await response.json()) as SeatMap;
  return response?.status === 404 ? "not-found" : "unavailable";
}

export async function createPnr(request: CreatePnrRequest, idempotencyKey: string): Promise<BookingOutcome> {
  const response = await call("/api/v1/pnrs", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(request),
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) return { kind: "booked", pnr: (await response.json()) as Pnr };
  if (response.status === 409) {
    return { kind: "seat-taken", detail: await problemDetail(response, "A selected seat was just taken.") };
  }
  if (response.status === 400 || response.status === 404 || response.status === 422) {
    return { kind: "rejected", detail: await problemDetail(response, "Please check your details.") };
  }
  return { kind: "unavailable" };
}

/** Last name travels in the query string server-to-server only; it never appears in a browser URL. */
export async function getPnr(locator: string, lastName: string): Promise<Pnr | "not-found" | "unavailable"> {
  const response = await call(
    `/api/v1/pnrs/${encodeURIComponent(locator)}?lastName=${encodeURIComponent(lastName)}`,
  );
  if (!response) return "unavailable";
  if (response.ok) return (await response.json()) as Pnr;
  if (response.status === 404 || response.status === 400) return "not-found";
  return "unavailable";
}
