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

export type HoldOutcome =
  | { kind: "held"; holdId: string; expiresAt: string }
  | { kind: "seat-taken"; detail: string }
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

export type HoldItem = components["schemas"]["HoldItem"];

/** Holds every seat or none for a few minutes, so nobody else can take them while the traveller checks out. */
export async function createHold(items: HoldItem[]): Promise<HoldOutcome> {
  const response = await call("/api/v1/holds", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ items }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) {
    const hold = (await response.json()) as components["schemas"]["Hold"];
    return { kind: "held", holdId: hold.holdId, expiresAt: hold.expiresAt };
  }
  if (response.status === 409) {
    return { kind: "seat-taken", detail: await problemDetail(response, "A selected seat was just taken.") };
  }
  if (response.status === 400 || response.status === 404 || response.status === 422) {
    return { kind: "rejected", detail: await problemDetail(response, "Please check your seats.") };
  }
  return { kind: "unavailable" };
}

/** Safe to repeat. Returns nothing: a hold that is already gone is the outcome we wanted. */
export async function releaseHold(holdId: string): Promise<void> {
  await call(`/api/v1/holds/${encodeURIComponent(holdId)}`, { method: "DELETE", cache: "no-store" });
}

export type SessionOutcome =
  | { kind: "opened"; accessToken: string; expiresAt: string; pnr: Pnr }
  | { kind: "not-found" }
  | { kind: "unavailable" };

/** What came back from a call that needs a manage token. */
export type ManageOutcome =
  | { kind: "ok"; pnr: Pnr }
  | { kind: "unauthorized" } // token missing, expired, or for another booking
  | { kind: "not-found" }
  | { kind: "seat-taken"; detail: string }
  | { kind: "cancelled"; detail: string }
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

export type SeatChange = components["schemas"]["SeatChange"];

/**
 * Proves ownership with a passenger's last name and returns a short-lived token for that booking.
 * An unknown reference and a wrong last name look the same, here and in the API.
 */
export async function openManageSession(locator: string, lastName: string): Promise<SessionOutcome> {
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/sessions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ lastName }),
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 201) {
    const session = (await response.json()) as components["schemas"]["ManageSession"];
    return { kind: "opened", accessToken: session.accessToken, expiresAt: session.expiresAt, pnr: session.pnr };
  }
  if (response.status === 404 || response.status === 400) return { kind: "not-found" };
  return { kind: "unavailable" };
}

async function managed(path: string, token: string, init: RequestInit = {}): Promise<ManageOutcome> {
  const response = await call(path, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) return { kind: "ok", pnr: (await response.json()) as Pnr };
  switch (response.status) {
    case 401:
    case 403:
      return { kind: "unauthorized" };
    case 404:
      return { kind: "not-found" };
    case 409: {
      const problem = (await response.json().catch(() => null)) as Problem | null;
      const detail = problem?.detail ?? "That change could not be made.";
      return problem?.title === "Booking cancelled" ? { kind: "cancelled", detail } : { kind: "seat-taken", detail };
    }
    case 400:
    case 422:
      return { kind: "rejected", detail: await problemDetail(response, "Please check your details.") };
    default:
      return { kind: "unavailable" };
  }
}

export function getManagedPnr(locator: string, token: string): Promise<ManageOutcome> {
  return managed(`/api/v1/pnrs/${encodeURIComponent(locator)}`, token);
}

export function cancelPnr(locator: string, token: string): Promise<ManageOutcome> {
  return managed(`/api/v1/pnrs/${encodeURIComponent(locator)}/cancellation`, token, { method: "POST" });
}

export function changeSeat(locator: string, token: string, change: SeatChange): Promise<ManageOutcome> {
  return managed(`/api/v1/pnrs/${encodeURIComponent(locator)}/seat-assignments`, token, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(change),
  });
}
