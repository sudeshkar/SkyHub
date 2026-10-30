"use server";

import { redirect } from "next/navigation";
import {
  openStaffSession,
  recordDisruption as recordDisruptionCall,
  type RecordDisruptionRequest,
} from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";
import { clearStaffSession, readStaffSession, saveStaffSession } from "@/lib/staff-session";

/** `username` is echoed back so the field keeps its value after React resets the form. */
export type LoginState = { status: "idle" } | { status: "error"; message: string; username: string };

export type RecordState =
  | { status: "idle" }
  | { status: "recorded"; flightNumber: string; departureDate: string; type: "DELAYED" | "CANCELLED"; affectedBookings: number }
  | { status: "error"; message: string; values: Record<string, string> }
  | { status: "signed-out" };

const FLIGHT = /^[A-Z0-9]{2}[0-9]{1,4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;
const MAX_REASON = 200;

/**
 * Posted from a form so the password never appears in a URL or a log. A wrong password and an unknown user
 * get the same message, as they do in the API. On success the token goes into an httpOnly cookie.
 */
export async function staffLogin(_previous: LoginState, form: FormData): Promise<LoginState> {
  const username = String(form.get("username") ?? "").trim().toLowerCase();
  const decision = await rateLimit(POLICIES.staffLogin);
  if (!decision.allowed) {
    const minutes = Math.ceil(decision.retryAfterSeconds / 60);
    return {
      status: "error",
      message: `Too many attempts. Please try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`,
      username,
    };
  }
  const password = String(form.get("password") ?? "");
  if (username.length === 0 || password.length === 0 || password.length > 200) {
    return { status: "error", message: "Enter your username and password.", username };
  }

  const result = await openStaffSession(username, password);
  if (result.kind === "denied") return { status: "error", message: "Wrong username or password.", username };
  if (result.kind === "unavailable") {
    return { status: "error", message: "The booking service is not responding right now. Please try again.", username };
  }
  await saveStaffSession({ username: result.username, role: result.role, token: result.accessToken }, result.expiresAt);
  redirect("/ops"); // outside any try/catch: redirect() works by throwing
}

/** Ends the session: the token is dropped from the browser. */
export async function logOut(): Promise<void> {
  await clearStaffSession();
  redirect("/ops");
}

/**
 * Records a delay or cancellation. The time is entered and stored in UTC, like every time in SkyHub (ADR-0005).
 * Whether this person may do it is decided by the booking service from their token, not by anything here.
 */
export async function recordDisruption(_previous: RecordState, form: FormData): Promise<RecordState> {
  const values = {
    flightNumber: String(form.get("flightNumber") ?? "").trim().toUpperCase(),
    departureDate: String(form.get("departureDate") ?? "").trim(),
    type: String(form.get("type") ?? ""),
    revisedDeparture: String(form.get("revisedDeparture") ?? "").trim(),
    reason: String(form.get("reason") ?? "").trim(),
  };
  const fail = (message: string): RecordState => ({ status: "error", message, values });

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return fail(`Too many requests. Please wait ${decision.retryAfterSeconds} seconds.`);

  const session = await readStaffSession();
  if (!session) return { status: "signed-out" };

  if (!FLIGHT.test(values.flightNumber)) return fail("Enter a flight number such as SK501.");
  if (!DATE.test(values.departureDate)) return fail("Enter the scheduled departure date.");
  if (values.type !== "DELAYED" && values.type !== "CANCELLED") return fail("Choose delayed or cancelled.");
  if (values.reason.length > MAX_REASON) return fail(`The reason can be at most ${MAX_REASON} characters.`);
  if (values.type === "DELAYED" && !DATE_TIME.test(values.revisedDeparture)) {
    return fail("A delay needs the new departure date and time.");
  }

  const request: RecordDisruptionRequest = {
    flightNumber: values.flightNumber,
    departureDate: values.departureDate,
    type: values.type,
    ...(values.type === "DELAYED" ? { revisedDeparture: `${values.revisedDeparture}:00Z` } : {}),
    ...(values.reason ? { reason: values.reason } : {}),
  };
  const outcome = await recordDisruptionCall(session.token, request);

  switch (outcome.kind) {
    case "recorded":
      return {
        status: "recorded",
        flightNumber: values.flightNumber,
        departureDate: values.departureDate,
        type: values.type,
        affectedBookings: outcome.affectedBookings,
      };
    case "unauthorized":
      await clearStaffSession(); // expired, or this role may not do it: back to the login
      return { status: "signed-out" };
    case "rejected":
      return fail(outcome.detail);
    default:
      return fail("The booking service is not responding right now. Please try again.");
  }
}
