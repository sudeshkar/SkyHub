"use server";

import { redirect } from "next/navigation";
import {
  cancelPnr,
  changeSeat as changeSeatCall,
  checkIn as checkInCall,
  checkOut as checkOutCall,
  getSeatMap,
  openManageSession,
  type CheckInOutcome,
  type CheckOutOutcome,
  type ManageOutcome,
  type SeatMap,
  type Segment,
} from "@/lib/api/booking";
import { clearSession, readSession, saveSession, type ManageSession } from "@/lib/manage-session";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

/** `locator` is echoed back so the field keeps its value after React resets the form. */
export type LookupState = { status: "idle" } | { status: "error"; message: string; locator: string };

export type ManageActionResult = ManageOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

const LOCATOR = /^[A-Z0-9]{6}$/;
const SEAT = /^[0-9]{1,2}[A-K]$/;
const FLIGHT = /^[A-Z0-9]{2}[0-9]{1,4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Posted from a form so the last name never appears in a URL, browser history or server logs.
 * Unknown reference and wrong name get the same message, so neither can be probed.
 * On success the token goes into an httpOnly cookie and the page reloads showing the booking.
 */
export async function lookupBooking(_previous: LookupState, form: FormData): Promise<LookupState> {
  const locator = String(form.get("locator") ?? "").trim().toUpperCase();
  const decision = await rateLimit(POLICIES.lookup);
  if (!decision.allowed) {
    const minutes = Math.ceil(decision.retryAfterSeconds / 60);
    return {
      status: "error",
      message: `Too many attempts. Please try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`,
      locator,
    };
  }
  const lastName = String(form.get("lastName") ?? "").trim();
  if (!LOCATOR.test(locator) || lastName.length === 0 || lastName.length > 50) {
    return {
      status: "error",
      message: "Enter your 6-character booking reference and a passenger's last name.",
      locator,
    };
  }

  const result = await openManageSession(locator, lastName);
  if (result.kind === "not-found") {
    return { status: "error", message: "We couldn't find a booking with that reference and last name.", locator };
  }
  if (result.kind === "unavailable") {
    return { status: "error", message: "Booking lookup is not responding right now. Please try again.", locator };
  }
  await saveSession({ locator, token: result.accessToken }, result.expiresAt);
  redirect("/manage"); // outside any try/catch: redirect() works by throwing
}

/** Ends the session: the token is dropped from the browser. */
export async function closeSession(): Promise<void> {
  await clearSession();
  redirect("/manage");
}

async function withSession(run: (session: ManageSession) => Promise<ManageOutcome>): Promise<ManageActionResult> {
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const session = await readSession();
  if (!session) return { kind: "unauthorized" };

  const outcome = await run(session);
  if (outcome.kind === "unauthorized") await clearSession(); // expired or for another booking: drop it
  return outcome;
}

export async function cancelBooking(): Promise<ManageActionResult> {
  return withSession((session) => cancelPnr(session.locator, session.token));
}

export async function changeSeat(change: {
  passengerIndex: number;
  segmentIndex: number;
  seatNumber: string;
}): Promise<ManageActionResult> {
  const valid =
    Number.isInteger(change.passengerIndex) &&
    change.passengerIndex >= 0 &&
    change.passengerIndex <= 8 &&
    Number.isInteger(change.segmentIndex) &&
    change.segmentIndex >= 0 &&
    change.segmentIndex <= 3 &&
    SEAT.test(change.seatNumber);
  if (!valid) return { kind: "rejected", detail: "Choose a valid seat." };

  return withSession((session) =>
    changeSeatCall(session.locator, session.token, {
      passengerIndex: change.passengerIndex,
      segmentIndex: change.segmentIndex,
      seatNumber: change.seatNumber,
    }),
  );
}

export type CheckInActionResult = CheckInOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

/** Checks in every passenger on one flight of the open booking and returns their boarding passes. */
export async function checkInFlight(segmentIndex: number): Promise<CheckInActionResult> {
  if (!Number.isInteger(segmentIndex) || segmentIndex < 0 || segmentIndex > 3) {
    return { kind: "rejected", detail: "Choose one of your flights." };
  }
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const session = await readSession();
  if (!session) return { kind: "unauthorized" };

  const outcome = await checkInCall(session.locator, session.token, segmentIndex);
  if (outcome.kind === "unauthorized") await clearSession();
  return outcome;
}

export type CheckOutActionResult = CheckOutOutcome | { kind: "rate-limited"; retryAfterSeconds: number };

/** Checks the whole party out of one flight of the open booking, so its seats can be changed again (ADR-0055). */
export async function checkOutFlight(segmentIndex: number): Promise<CheckOutActionResult> {
  if (!Number.isInteger(segmentIndex) || segmentIndex < 0 || segmentIndex > 3) {
    return { kind: "rejected", detail: "Choose one of your flights." };
  }
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { kind: "rate-limited", retryAfterSeconds: decision.retryAfterSeconds };

  const session = await readSession();
  if (!session) return { kind: "unauthorized" };

  const outcome = await checkOutCall(session.locator, session.token, segmentIndex);
  if (outcome.kind === "unauthorized") await clearSession();
  return outcome;
}

/** Seat maps are public, so no session is needed; the input is still checked. */
export async function loadSeatMap(segment: Segment): Promise<SeatMap | null> {
  if (!FLIGHT.test(segment.flightNumber) || !DATE.test(segment.departureDate)) return null;
  const result = await getSeatMap(segment);
  return typeof result === "string" ? null : result;
}
