"use client";

import { useActionState } from "react";
import { withdrawDisruption, type WithdrawState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

/**
 * Takes back a disruption that was recorded by mistake or has been resolved (ADR-0026). Kept apart from the recording form
 * on purpose: it is rarer, and it asks for a confirmation the other form does not.
 */
export function WithdrawForm() {
  const [state, action, pending] = useActionState<WithdrawState, FormData>(withdrawDisruption, { status: "idle" });
  const typed = state.status === "error" ? state.values : undefined;

  if (state.status === "signed-out") {
    return (
      <p role="alert" className="mt-10 border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
        Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
      </p>
    );
  }

  return (
    <section aria-label="Withdraw a disruption" className="mt-10 space-y-6 border-t border-sand pt-8">
      {state.status === "withdrawn" && (
        <div role="status" className="border-l-4 border-copper bg-white px-4 py-3">
          <p className="font-semibold">
            {state.flightNumber} on {state.departureDate} is back to its schedule.
          </p>
          <p className="mt-1 text-sm text-muted">
            It is for sale again and search shows it as scheduled.{" "}
            {state.affectedBookings === 0
              ? "No active bookings are on this flight."
              : `${state.affectedBookings} active booking${state.affectedBookings === 1 ? " is" : "s are"} on this flight.`}
          </p>
        </div>
      )}

      <form action={action} className="max-w-md space-y-4">
        <h2 className="font-display text-xl">Withdraw a disruption</h2>
        <p className="text-sm text-muted">
          Use this when a delay or cancellation was recorded by mistake or has been resolved. Every traveller still holding the
          flight is emailed that it is operating again. It cannot be done once bookings have been moved off the flight.
        </p>
        <label className="block text-sm font-medium">
          Flight number
          <input
            name="flightNumber"
            defaultValue={typed?.flightNumber ?? ""}
            required
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            placeholder="SK501"
            className={`${field} font-mono uppercase tracking-widest`}
          />
        </label>
        <label className="block text-sm font-medium">
          Scheduled departure date (UTC)
          <input name="departureDate" type="date" defaultValue={typed?.departureDate ?? ""} required className={field} />
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" name="confirm" value="yes" required />
          I am sure I want to withdraw this disruption
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md border border-copper px-6 py-3 font-semibold text-copper hover:bg-white disabled:opacity-60"
        >
          {pending ? "Withdrawing…" : "Withdraw"}
        </button>
        {state.status === "error" && (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        )}
      </form>
    </section>
  );
}
