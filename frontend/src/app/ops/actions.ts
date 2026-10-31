"use server";

import { redirect } from "next/navigation";
import { getProposals, type ProposalSet } from "@/lib/api/agent";
import {
  boardPassenger,
  type BulkMoveResult,
  type GateReason,
  openStaffSession,
  rebookFlights,
  recordDisruption as recordDisruptionCall,
  type RecordDisruptionRequest,
  verifyBoardingPass,
  withdrawDisruption as withdrawDisruptionCall,
} from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";
import { clearStaffSession, readStaffSession, saveStaffSession } from "@/lib/staff-session";
import { MAX_MOVES_PER_CALL } from "./limits";

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

export type WithdrawState =
  | { status: "idle" }
  | { status: "withdrawn"; flightNumber: string; departureDate: string; affectedBookings: number }
  | { status: "error"; message: string; values: { flightNumber: string; departureDate: string } }
  | { status: "signed-out" };

/**
 * Takes a disruption back (ADR-0026). Needs an explicit confirmation, because travellers who were emailed are not
 * emailed again. Whether this person may do it, and whether it is allowed at all, is decided by the booking service.
 */
export async function withdrawDisruption(_previous: WithdrawState, form: FormData): Promise<WithdrawState> {
  const values = {
    flightNumber: String(form.get("flightNumber") ?? "").trim().toUpperCase(),
    departureDate: String(form.get("departureDate") ?? "").trim(),
  };
  const fail = (message: string): WithdrawState => ({ status: "error", message, values });

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return fail(`Too many requests. Please wait ${decision.retryAfterSeconds} seconds.`);

  const session = await readStaffSession();
  if (!session) return { status: "signed-out" };

  if (!FLIGHT.test(values.flightNumber)) return fail("Enter a flight number such as SK501.");
  if (!DATE.test(values.departureDate)) return fail("Enter the scheduled departure date.");
  if (!form.get("confirm")) return fail("Please confirm that you want to withdraw this disruption.");

  const outcome = await withdrawDisruptionCall(session.token, values.flightNumber, values.departureDate);
  switch (outcome.kind) {
    case "withdrawn":
      return { status: "withdrawn", ...values, affectedBookings: outcome.affectedBookings };
    case "unauthorized":
      await clearStaffSession();
      return { status: "signed-out" };
    case "refused":
      return fail(outcome.detail);
    default:
      return fail("The booking service is not responding right now. Please try again.");
  }
}

export type ProposalsState =
  | { status: "idle" }
  | { status: "loaded"; set: ProposalSet }
  | { status: "error"; message: string; values: { flightNumber: string; departureDate: string } }
  | { status: "signed-out" };

/**
 * Asks the disruption agent what to do with everyone booked on a disrupted flight (ADR-0024). It only proposes: the answer
 * holds references and counts, never names, and nothing is changed until staff approve each move.
 */
export async function loadProposals(_previous: ProposalsState, form: FormData): Promise<ProposalsState> {
  const values = {
    flightNumber: String(form.get("flightNumber") ?? "").trim().toUpperCase(),
    departureDate: String(form.get("departureDate") ?? "").trim(),
  };
  const fail = (message: string): ProposalsState => ({ status: "error", message, values });

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return fail(`Too many requests. Please wait ${decision.retryAfterSeconds} seconds.`);
  if (!(await readStaffSession())) return { status: "signed-out" };
  if (!FLIGHT.test(values.flightNumber)) return fail("Enter a flight number such as SK501.");
  if (!DATE.test(values.departureDate)) return fail("Enter the scheduled departure date.");

  const outcome = await getProposals(values.flightNumber, values.departureDate);
  switch (outcome.kind) {
    case "ok":
      return { status: "loaded", set: outcome.set };
    case "busy":
      return fail("The agent is busy right now. Try again in a moment.");
    case "rejected":
      return fail(outcome.detail);
    default:
      return fail("The agent is not responding right now. Please try again.");
  }
}

export type Move = { locator: string; segmentIndex: number; flightNumber: string; departureDate: string };
export type MoveResult = { locator: string; status: "moved" | "unchanged" | "refused" | "failed"; detail?: string; seats?: string[] };
export type MoveBatchResult =
  | { status: "done"; results: MoveResult[] }
  | { status: "signed-out"; results: MoveResult[] }
  | { status: "error"; message: string };

const LOCATOR = /^[A-Z0-9]{6}$/;

/**
 * Carries out the moves a person approved, in one call to the booking service (ADR-0035), with their own staff token, so the
 * audit names whoever approved. The service re-checks every rule and may refuse any of them; each refusal is reported with its
 * reason and the rest carry on. A refused token drops the session. When the service stops partway, the moves it did not try
 * are left out of the answer, which is how the page knows to stop sending more batches.
 */
export async function moveBookings(moves: Move[]): Promise<MoveBatchResult> {
  if (moves.length < 1 || moves.length > MAX_MOVES_PER_CALL) {
    return { status: "error", message: `Move between 1 and ${MAX_MOVES_PER_CALL} bookings at a time.` };
  }
  const valid = moves.every(
    (m) => LOCATOR.test(m.locator) && Number.isInteger(m.segmentIndex) && m.segmentIndex >= 0 && m.segmentIndex <= 3 &&
      FLIGHT.test(m.flightNumber) && DATE.test(m.departureDate),
  );
  if (!valid) return { status: "error", message: "One of the moves is not valid. Nothing was changed." };

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { status: "error", message: `Too many requests. Please wait ${decision.retryAfterSeconds} seconds.` };
  const session = await readStaffSession();
  if (!session) return { status: "signed-out", results: [] };

  const outcome = await rebookFlights(session.token, moves);
  switch (outcome.kind) {
    case "unauthorized":
      await clearStaffSession(); // expired, or this role may not do it: back to the login
      return { status: "signed-out", results: [] };
    case "rejected":
      return { status: "error", message: outcome.detail };
    case "unavailable":
      return { status: "error", message: "The booking service is not responding. Nothing may have been moved; check and try again." };
    default:
      return { status: "done", results: outcome.results.filter((r) => r.outcome !== "NOT_ATTEMPTED").map(toMoveResult) };
  }
}

function toMoveResult(result: BulkMoveResult): MoveResult {
  switch (result.outcome) {
    case "MOVED":
      return { locator: result.locator, status: "moved", seats: result.seats ?? [] };
    case "UNCHANGED":
      return { locator: result.locator, status: "unchanged", seats: result.seats ?? [] };
    case "REFUSED":
      return { locator: result.locator, status: "refused", detail: result.detail ?? "A rule forbids this move." };
    case "NOT_FOUND":
      return { locator: result.locator, status: "failed", detail: "That booking was not found." };
    default:
      return { locator: result.locator, status: "failed", detail: result.detail ?? "This move could not be completed." };
  }
}

export type GateRefusal = GateReason;

/** Only what the gate needs to see; the pass text and the booking reference never go back to the browser. */
export type GatePass = { passengerName: string; flightNumber: string; departureDate: string; seatNumber: string; sequence: number };

export type GateState =
  | { status: "idle" }
  | { status: "good"; intent: "check" | "board"; pass: GatePass }
  | { status: "refused"; reason: GateRefusal }
  | { status: "error"; message: string }
  | { status: "signed-out" };

const MAX_PASS = 300;

/**
 * A gate scans a pass (ADR-0062). The button pressed says whether this only checks the pass or also boards the passenger; anything but an
 * explicit "board" only checks, so a stray submit can never board anyone. Whether this person may is decided by the booking service from
 * their token. The pass text is passed on and never logged or sent back.
 */
export async function gateScan(_previous: GateState, form: FormData): Promise<GateState> {
  const barcode = String(form.get("barcode") ?? "").trim();
  const board = form.get("intent") === "board";
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { status: "error", message: `Too many requests. Please wait ${decision.retryAfterSeconds} seconds.` };
  if (barcode.length === 0 || barcode.length > MAX_PASS) return { status: "error", message: "Scan or paste a boarding pass." };

  const session = await readStaffSession();
  if (!session) return { status: "signed-out" };

  const outcome = board ? await boardPassenger(session.token, barcode) : await verifyBoardingPass(session.token, barcode);
  switch (outcome.kind) {
    case "good": {
      const { passengerName, flightNumber, departureDate, seatNumber, sequence } = outcome.pass;
      return { status: "good", intent: board ? "board" : "check", pass: { passengerName, flightNumber, departureDate, seatNumber, sequence } };
    }
    case "refused":
      return { status: "refused", reason: outcome.reason };
    case "unauthorized":
      await clearStaffSession(); // expired, or a role that may not do this: the cookie is no use any more
      return { status: "signed-out" };
    case "rejected":
      return { status: "error", message: outcome.detail };
    default:
      return { status: "error", message: "The booking service is not responding right now, so nothing was changed. Please try again." };
  }
}
