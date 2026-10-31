"use client";

import { useActionState } from "react";
import { gateScan, type GateRefusal, type GateState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5 font-mono";

/** What the gate agent is told when a pass is refused: what to do, never who the pass was for (ADR-0056). */
const REFUSALS: Record<GateRefusal, string> = {
  BAD_SIGNATURE: "This is not a SkyHub boarding pass, or it has been changed. Do not board.",
  UNKNOWN_BOOKING: "This pass has no booking behind it. Do not board.",
  BOOKING_CANCELLED: "This booking was cancelled. Do not board.",
  FLIGHT_CANCELLED: "This flight was cancelled. Do not board.",
  NOT_CHECKED_IN: "This passenger is not checked in (they may have checked out). Send them to check in.",
  SUPERSEDED: "This is an old pass; a newer one was issued after a seat change or a new check-in. Ask for the current pass.",
  ALREADY_BOARDED: "This passenger has already boarded. Do not board them again.",
};

/**
 * The gate (ADR-0062): scan or paste a boarding pass, check it, and board the passenger. A scanner types the pass like a keyboard, so
 * the field is all it needs. Checking never boards anyone; boarding is its own button. The pass is not shown back and nothing is kept.
 */
export function GatePanel() {
  const [state, action, pending] = useActionState<GateState, FormData>(gateScan, { status: "idle" });

  return (
    <section aria-label="Gate" className="mt-10 space-y-6 border-t border-sand pt-8">
      <h2 className="font-display text-xl">Gate</h2>
      <p className="max-w-md text-sm text-muted">
        Scan or paste a boarding pass. Checking a pass does not board the passenger; press Board passenger to board them. A passenger boards
        once.
      </p>

      {state.status === "good" && (
        <div role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
          <p className="font-semibold">
            {state.intent === "board" ? `${state.pass.passengerName} has boarded.` : `Good pass for ${state.pass.passengerName}.`}
          </p>
          <p className="mt-1 text-sm">
            Flight {state.pass.flightNumber} on {state.pass.departureDate}, seat {state.pass.seatNumber}, boarding number {state.pass.sequence}.
          </p>
          {state.intent === "check" && <p className="mt-1 text-sm text-muted">Not boarded yet. Press Board passenger to board them.</p>}
        </div>
      )}
      {state.status === "refused" && (
        <p role="alert" className="max-w-md border-l-4 border-danger bg-white px-4 py-3 font-semibold text-danger">
          {REFUSALS[state.reason]}
        </p>
      )}
      {state.status === "error" && (
        <p role="alert" className="max-w-md border-l-4 border-danger bg-white px-4 py-3 text-sm text-danger">
          {state.message}
        </p>
      )}
      {state.status === "signed-out" && (
        <p role="alert" className="max-w-md border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
          Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
        </p>
      )}

      <form action={action} className="max-w-md space-y-4">
        <label className="block text-sm font-medium">
          Scanned boarding pass
          <input name="barcode" required maxLength={300} autoComplete="off" autoFocus spellCheck={false} className={field} />
        </label>
        <div className="flex flex-wrap gap-3">
          <button
            type="submit"
            name="intent"
            value="check"
            disabled={pending}
            className="rounded-md border border-copper px-6 py-3 font-semibold text-copper hover:bg-sand-soft disabled:opacity-60"
          >
            Check pass
          </button>
          <button
            type="submit"
            name="intent"
            value="board"
            disabled={pending}
            className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
          >
            Board passenger
          </button>
        </div>
      </form>
    </section>
  );
}
