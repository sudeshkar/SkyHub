"use client";

import { useActionState } from "react";
import { boardLate, type LateBoardingState } from "./actions";
import { REFUSALS } from "./gate-reasons";

const field = "mt-1 block w-full rounded-md border border-line bg-white px-3 py-2.5";

/**
 * Boards a passenger after the gate has closed (ADR-0076). For operations and administrators only: the page does not show it to a gate
 * agent, and the booking service would refuse them anyway, because it overrules the gate. It asks for the pass, a reason and a
 * confirmation, because the boarding is kept on record with the staff member's name and the reason. It only works once boarding has
 * closed and before the flight is due to have left; the booking service says which of those is wrong.
 */
export function LateBoardingForm() {
  const [state, action, pending] = useActionState<LateBoardingState, FormData>(boardLate, { status: "idle" });

  if (state.status === "signed-out") {
    return (
      <p role="alert" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
        Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
      </p>
    );
  }

  return (
    <section aria-label="Board a late passenger" className="space-y-6">
      {state.status === "boarded" && (
        <div role="status" className="max-w-md border-l-4 border-accent bg-white px-4 py-3">
          <p className="font-semibold">{state.pass.passengerName} has been boarded late.</p>
          <p className="mt-1 text-sm">
            Flight {state.pass.flightNumber} on {state.pass.departureDate}, seat {state.pass.seatNumber}, boarding number {state.pass.sequence}.
          </p>
          <p className="mt-1 text-sm text-muted">Your name and reason were recorded with the boarding.</p>
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

      <form action={action} className="tool-form">
        <h2 className="font-display text-xl">Board a late passenger</h2>
        <p className="text-sm text-muted">
          Use this only for a passenger who reaches the gate after the gate has closed, while the flight has not yet left. A gate agent cannot do
          this. Your name and the reason are recorded, and the flight&apos;s boarding list marks the boarding as late.
        </p>
        <label className="block text-sm font-medium">
          Boarding pass
          <input name="barcode" required maxLength={300} autoComplete="off" spellCheck={false} className={`${field} font-mono`} />
        </label>
        <label className="block text-sm font-medium">
          Reason
          <textarea name="reason" required maxLength={200} rows={2} className={field} />
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" name="confirm" value="yes" required />
          I am sure I want to board this passenger after the gate has closed
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md border border-accent px-6 py-3 font-semibold text-accent hover:bg-white disabled:opacity-60"
        >
          {pending ? "Boarding…" : "Board late"}
        </button>
      </form>
    </section>
  );
}
