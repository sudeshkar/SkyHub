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
  | { kind: "declined"; detail: string } // the payment was refused; nothing was booked
  | { kind: "price-changed"; detail: string } // nothing was charged
  | { kind: "payment-unavailable" } // the payment provider did not answer; nothing was charged
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

export type QuoteOutcome = { kind: "quoted"; amount: number; currency: string } | { kind: "unavailable" };

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

/** The price of a trip as the booking service works it out (the same number it will charge). */
export async function getQuote(segments: Segment[], passengerCount: number): Promise<QuoteOutcome> {
  const response = await call("/api/v1/quotes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ segments, passengerCount }),
    cache: "no-store",
  });
  if (!response?.ok) return { kind: "unavailable" };
  const quote = (await response.json()) as components["schemas"]["Quote"];
  return { kind: "quoted", amount: quote.amount, currency: quote.currency };
}

export async function createPnr(request: CreatePnrRequest, idempotencyKey: string): Promise<BookingOutcome> {
  const response = await call("/api/v1/pnrs", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(request),
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) return { kind: "booked", pnr: (await response.json()) as Pnr };
  if (response.status === 402) {
    return { kind: "declined", detail: await problemDetail(response, "Your payment was declined.") };
  }
  if (response.status === 409) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    return problem?.title === "Price changed"
      ? { kind: "price-changed", detail: problem.detail ?? "The price has changed." }
      : { kind: "seat-taken", detail: problem?.detail ?? "A selected seat was just taken." };
  }
  if (response.status === 503) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    return problem?.title === "Payment unavailable" ? { kind: "payment-unavailable" } : { kind: "unavailable" };
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

export type BoardingPass = components["schemas"]["BoardingPass"];

export type CheckInOutcome =
  | { kind: "ok"; passes: BoardingPass[]; created: boolean }
  | { kind: "unauthorized" }
  | { kind: "not-found" }
  | { kind: "closed"; detail: string } // not open yet, or already closed
  | { kind: "cancelled"; detail: string }
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

async function passesOutcome(response: Response | null, okStatuses: number[]): Promise<CheckInOutcome> {
  if (!response) return { kind: "unavailable" };
  if (okStatuses.includes(response.status)) {
    const body = (await response.json()) as components["schemas"]["BoardingPasses"];
    return { kind: "ok", passes: body.passes, created: response.status === 201 };
  }
  switch (response.status) {
    case 401:
    case 403:
      return { kind: "unauthorized" };
    case 404:
      return { kind: "not-found" };
    case 409: {
      const problem = (await response.json().catch(() => null)) as Problem | null;
      const detail = problem?.detail ?? "Check-in is not possible right now.";
      return problem?.title === "Booking cancelled" ? { kind: "cancelled", detail } : { kind: "closed", detail };
    }
    case 400:
    case 422:
      return { kind: "rejected", detail: await problemDetail(response, "Please check your details.") };
    default:
      return { kind: "unavailable" };
  }
}

/**
 * Checks in the named passengers on one flight, or everyone not yet in when none are named (ADR-0058). Repeating it returns the same
 * passes. The answer holds the passes of everyone who is checked in on the flight afterwards.
 */
export async function checkIn(locator: string, token: string, segmentIndex: number, passengerIndexes?: number[]): Promise<CheckInOutcome> {
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/check-ins`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(passengerIndexes && passengerIndexes.length > 0 ? { segmentIndex, passengerIndexes } : { segmentIndex }),
    cache: "no-store",
  });
  return passesOutcome(response, [200, 201]);
}

export type EndManageSessionsOutcome =
  | { kind: "ended" } // every session of the booking, this one included and any copy of its token, is now refused
  | { kind: "unauthorized" } // this session was already not accepted: there is nothing left to end
  | { kind: "unavailable" };

/** Signs out of the booking everywhere (ADR-0057). It asks for nothing more than the session: it can only make the booking harder to reach. */
export async function endManageSessions(locator: string, token: string): Promise<EndManageSessionsOutcome> {
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/sessions`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 204) return { kind: "ended" };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type CheckOutOutcome =
  | { kind: "ok" } // the flight is not checked in now (it also was not, if it never was)
  | { kind: "closed"; detail: string } // check-in has closed: the passes are what the gate works from
  | { kind: "cancelled"; detail: string }
  | { kind: "unauthorized" }
  | { kind: "not-found" }
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

/**
 * Checks the named passengers out of one flight, or everyone who is in when none are named (ADR-0055, ADR-0058), so their seats can be
 * changed again. Repeating it is harmless.
 */
export async function checkOut(locator: string, token: string, segmentIndex: number, passengerIndexes?: number[]): Promise<CheckOutOutcome> {
  const who = (passengerIndexes ?? []).map((index) => `passengerIndex=${index}`).join("&");
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/check-ins/${segmentIndex}${who ? `?${who}` : ""}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  switch (response.status) {
    case 204:
      return { kind: "ok" };
    case 401:
    case 403:
      return { kind: "unauthorized" };
    case 404:
      return { kind: "not-found" };
    case 409: {
      const problem = (await response.json().catch(() => null)) as Problem | null;
      const detail = problem?.detail ?? "You can't check out right now.";
      return problem?.title === "Booking cancelled" ? { kind: "cancelled", detail } : { kind: "closed", detail };
    }
    case 400:
    case 422:
      return { kind: "rejected", detail: await problemDetail(response, "Please check your details.") };
    default:
      return { kind: "unavailable" };
  }
}

/** "not-found" here means the flight is not checked in (or the booking is gone). */
export async function getBoardingPasses(locator: string, token: string, segmentIndex: number): Promise<CheckInOutcome> {
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/check-ins/${segmentIndex}`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  return passesOutcome(response, [200]);
}

export type Disruption = components["schemas"]["Disruption"];
export type RecordDisruptionRequest = components["schemas"]["RecordDisruptionRequest"];

export type StaffOutcome =
  | { kind: "opened"; accessToken: string; expiresAt: string; username: string; role: "OPS" | "ADMIN" }
  | { kind: "denied" } // wrong username or password, or a locked account: the service does not say which
  | { kind: "unavailable" };

export type RecordOutcome =
  | { kind: "recorded"; affectedBookings: number }
  | { kind: "unauthorized" } // token missing, expired, or the role may not do this
  | { kind: "rejected"; detail: string }
  | { kind: "unavailable" };

/** Staff login (ADR-0017). Server-side only: the browser never sees the password go anywhere but here. */
export async function openStaffSession(username: string, password: string): Promise<StaffOutcome> {
  const response = await call("/api/v1/ops/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 201) {
    const session = (await response.json()) as components["schemas"]["StaffSession"];
    return {
      kind: "opened",
      accessToken: session.accessToken,
      expiresAt: session.expiresAt,
      username: session.username,
      role: session.role,
    };
  }
  if (response.status === 401 || response.status === 400) return { kind: "denied" };
  return { kind: "unavailable" };
}

export type PasswordLinkOutcome = { kind: "sent" } | { kind: "rejected"; detail: string } | { kind: "unavailable" };

/** Asks for an emailed link to choose a password: signs up a new traveller or resets a forgotten password (ADR-0033). */
export async function requestPasswordLink(email: string): Promise<PasswordLinkOutcome> {
  const response = await call("/api/v1/accounts/password-links", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 202) return { kind: "sent" };
  if (response.status === 400) return { kind: "rejected", detail: await problemDetail(response, "Enter a valid email address.") };
  return { kind: "unavailable" };
}

export type SetPasswordOutcome =
  | { kind: "set" }
  | { kind: "invalid-link" } // wrong, used or expired: the service does not say which
  | { kind: "rejected"; detail: string } // the password was not acceptable
  | { kind: "unavailable" };

export async function setAccountPassword(token: string, password: string): Promise<SetPasswordOutcome> {
  const response = await call("/api/v1/accounts/passwords", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, password }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 204) return { kind: "set" };
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    if (problem?.title === "Link not valid") return { kind: "invalid-link" };
    return { kind: "rejected", detail: problem?.detail ?? "That password was not accepted." };
  }
  return { kind: "unavailable" };
}

export type AccountSignInOutcome =
  | { kind: "opened"; accessToken: string; expiresAt: string; email: string }
  | { kind: "denied" } // wrong address or password, no password yet, or locked: the service does not say which
  | { kind: "unavailable" };

export async function openAccountSession(email: string, password: string): Promise<AccountSignInOutcome> {
  const response = await call("/api/v1/accounts/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 201) {
    const session = (await response.json()) as components["schemas"]["AccountSession"];
    return { kind: "opened", accessToken: session.accessToken, expiresAt: session.expiresAt, email: session.email };
  }
  if (response.status === 401 || response.status === 400) return { kind: "denied" };
  return { kind: "unavailable" };
}

export type MyAccountOutcome = { kind: "account"; email: string } | { kind: "unauthorized" } | { kind: "unavailable" };

export async function getMyAccount(token: string): Promise<MyAccountOutcome> {
  const response = await call("/api/v1/accounts/me", { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
  if (!response) return { kind: "unavailable" };
  if (response.ok) return { kind: "account", email: ((await response.json()) as components["schemas"]["Account"]).email };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type EmailChangeOutcome =
  | { kind: "sent" } // a link is on its way if the new address can be used; the service never says whether it can
  | { kind: "password-refused" } // wrong, missing or locked: a 403, so the session is not ended
  | { kind: "rejected"; detail: string } // not an address, or the account's own address
  | { kind: "unauthorized" }
  | { kind: "unavailable" };

/** Asks to move the signed-in account to another address (ADR-0039). A link goes to the NEW address. */
export async function requestAccountEmailChange(token: string, newEmail: string, password: string): Promise<EmailChangeOutcome> {
  const response = await call("/api/v1/accounts/me/email-change", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ newEmail, password }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 202) return { kind: "sent" };
  if (response.status === 403) return { kind: "password-refused" };
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    return { kind: "rejected", detail: problem?.detail ?? "That address was not accepted." };
  }
  if (response.status === 401) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type ConfirmEmailOutcome =
  | { kind: "changed" }
  | { kind: "invalid-link" } // wrong, used, expired, someone else's, or the address was taken meanwhile: the service does not say which
  | { kind: "unauthorized" }
  | { kind: "unavailable" };

/** Spends the link sent to the new address, for the signed-in account (ADR-0039). */
export async function confirmAccountEmailChange(token: string, linkToken: string): Promise<ConfirmEmailOutcome> {
  const response = await call("/api/v1/accounts/me/email-change/confirmation", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ token: linkToken }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 204) return { kind: "changed" };
  if (response.status === 400) return { kind: "invalid-link" };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type UndoEmailOutcome =
  | { kind: "undone" }
  | { kind: "invalid-link" } // wrong, used, expired, or the old address was taken since: the service does not say which
  | { kind: "unavailable" };

/**
 * Takes an account back to the address it had before its latest move (ADR-0054). It needs no sign-in: the token went to the OLD
 * address, and the account may be lost.
 */
export async function undoAccountEmailChange(linkToken: string): Promise<UndoEmailOutcome> {
  const response = await call("/api/v1/accounts/email-change-reversals", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: linkToken }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 204) return { kind: "undone" };
  if (response.status === 400) return { kind: "invalid-link" };
  return { kind: "unavailable" };
}

export type EndSessionsOutcome =
  | { kind: "ended" } // every session of the account, this one included, is now refused
  | { kind: "unauthorized" } // this session was already not accepted: there is nothing left to end
  | { kind: "unavailable" };

/** Signs the account out on every device (ADR-0047). It asks for no password: it can only make the account harder to use. */
export async function endAllAccountSessions(token: string): Promise<EndSessionsOutcome> {
  const response = await call("/api/v1/accounts/me/sessions", { method: "DELETE", headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
  if (!response) return { kind: "unavailable" };
  if (response.status === 204) return { kind: "ended" };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type MyBooking = components["schemas"]["BookingSummary"];

export type MyBookingsOutcome =
  | { kind: "bookings"; bookings: MyBooking[]; nextCursor: string | null } // nextCursor is null on the last page
  | { kind: "bad-cursor" } // the page asked for is not one this service made
  | { kind: "unauthorized" }
  | { kind: "unavailable" };

/** One page of the bookings made with the signed-in traveller's address, newest first (ADR-0034, ADR-0038). */
export async function listMyBookings(token: string, cursor?: string, when?: "upcoming" | "past"): Promise<MyBookingsOutcome> {
  const params = new URLSearchParams();
  if (cursor) params.set("cursor", cursor);
  if (when) params.set("when", when);
  const query = params.size > 0 ? `?${params}` : "";
  const response = await call(`/api/v1/accounts/me/bookings${query}`, { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
  if (!response) return { kind: "unavailable" };
  if (response.ok) {
    const list = (await response.json()) as components["schemas"]["MyBookings"];
    return { kind: "bookings", bookings: list.bookings, nextCursor: list.nextCursor ?? null };
  }
  if (response.status === 400) return { kind: "bad-cursor" };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type MyBookingSessionOutcome =
  | { kind: "opened"; accessToken: string; expiresAt: string }
  | { kind: "not-found" } // not this account's, or no such booking: the service does not say which
  | { kind: "unauthorized" }
  | { kind: "unavailable" };

/** Opens one of the traveller's own bookings: the answer is the ordinary manage token for that booking. */
export async function openMyBookingSession(token: string, locator: string): Promise<MyBookingSessionOutcome> {
  const response = await call(`/api/v1/accounts/me/bookings/${encodeURIComponent(locator)}/sessions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.status === 201) {
    const session = (await response.json()) as components["schemas"]["ManageSession"];
    return { kind: "opened", accessToken: session.accessToken, expiresAt: session.expiresAt };
  }
  if (response.status === 404) return { kind: "not-found" };
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type BulkMove = { locator: string; segmentIndex: number; flightNumber: string; departureDate: string };
export type BulkMoveResult = components["schemas"]["MoveResult"];

export type BulkRebookOutcome =
  | { kind: "results"; results: BulkMoveResult[] } // one per move, in the order sent; each move succeeded or not on its own
  | { kind: "rejected"; detail: string } // the whole set was refused (too many, or a move listed twice): nothing changed
  | { kind: "unauthorized" } // token missing, expired, or the role may not do this
  | { kind: "unavailable" };

/** Moves many bookings in one call (ADR-0035). Each move is all or nothing; the call is not. Repeating it is safe. */
export async function rebookFlights(token: string, moves: BulkMove[]): Promise<BulkRebookOutcome> {
  const response = await call("/api/v1/ops/rebookings", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ moves }),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) return { kind: "results", results: ((await response.json()) as components["schemas"]["BulkRebooked"]).results };
  if (response.status === 400) {
    const problem = (await response.json().catch(() => null)) as Problem | null;
    return { kind: "rejected", detail: problem?.detail ?? "These moves were not accepted." };
  }
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  return { kind: "unavailable" };
}

export type RebookOutcome =
  | { kind: "moved" | "unchanged"; seats: string[] } // unchanged: it was already on that flight
  | { kind: "refused"; detail: string } // a rule forbids the move, or the seats are not there: the reason is for staff
  | { kind: "unauthorized" } // token missing, expired, or the role may not do this
  | { kind: "not-found" }
  | { kind: "unavailable" };

export type RebookMove = { segmentIndex: number; flightNumber: string; departureDate: string };

/** Moves one flight of a booking onto another. The staff token says who did it, and the booking service re-checks every rule. */
export async function rebookFlight(token: string, locator: string, move: RebookMove): Promise<RebookOutcome> {
  const response = await call(`/api/v1/ops/pnrs/${encodeURIComponent(locator)}/rebooking`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(move),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) {
    const moved = (await response.json()) as components["schemas"]["Rebooked"];
    return { kind: moved.changed ? "moved" : "unchanged", seats: moved.seats };
  }
  switch (response.status) {
    case 401:
    case 403:
      return { kind: "unauthorized" };
    case 404:
      return { kind: "not-found" };
    case 400:
    case 409:
      return { kind: "refused", detail: await problemDetail(response, "That move was not accepted.") };
    default:
      return { kind: "unavailable" };
  }
}

/** Records a delay or cancellation; the staff token says who did it. */
export async function recordDisruption(token: string, request: RecordDisruptionRequest): Promise<RecordOutcome> {
  const response = await call("/api/v1/ops/disruptions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(request),
    cache: "no-store",
  });
  if (!response) return { kind: "unavailable" };
  if (response.ok) {
    const recorded = (await response.json()) as components["schemas"]["DisruptionRecorded"];
    return { kind: "recorded", affectedBookings: recorded.affectedBookings };
  }
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  if (response.status === 400) return { kind: "rejected", detail: await problemDetail(response, "That was not accepted.") };
  return { kind: "unavailable" };
}

export type WithdrawOutcome =
  | { kind: "withdrawn"; affectedBookings: number }
  | { kind: "unauthorized" }
  | { kind: "refused"; detail: string } // nothing to withdraw (404), or bookings were moved off the flight (409)
  | { kind: "unavailable" };

/** Takes a disruption back (ADR-0026); the staff token says who did it. */
export async function withdrawDisruption(token: string, flightNumber: string, departureDate: string): Promise<WithdrawOutcome> {
  const response = await call(
    `/api/v1/ops/disruptions/${encodeURIComponent(flightNumber)}/${encodeURIComponent(departureDate)}`,
    { method: "DELETE", headers: { Authorization: `Bearer ${token}` }, cache: "no-store" },
  );
  if (!response) return { kind: "unavailable" };
  if (response.ok) {
    const withdrawn = (await response.json()) as components["schemas"]["DisruptionRecorded"];
    return { kind: "withdrawn", affectedBookings: withdrawn.affectedBookings };
  }
  if (response.status === 401 || response.status === 403) return { kind: "unauthorized" };
  if (response.status === 400 || response.status === 404 || response.status === 409) {
    return { kind: "refused", detail: await problemDetail(response, "That disruption could not be withdrawn.") };
  }
  return { kind: "unavailable" };
}

/** A failure here must not break the page, so it quietly means "nothing to show". */
export async function getDisruptions(locator: string, token: string): Promise<Disruption[]> {
  const response = await call(`/api/v1/pnrs/${encodeURIComponent(locator)}/disruptions`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response?.ok) return [];
  return ((await response.json()) as components["schemas"]["BookingDisruptions"]).items;
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
