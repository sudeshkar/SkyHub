"use client";

import { useActionState, useState } from "react";
import { recordDisruption, type RecordState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

/**
 * Records a delay or cancellation. Recording again for the same flight and date replaces the earlier record, so a
 * correction is the same form (ADR-0014). Times are UTC, like everything in SkyHub.
 */
export function DisruptionForm() {
  const [state, action, pending] = useActionState<RecordState, FormData>(recordDisruption, { status: "idle" });
  const typed = state.status === "error" ? state.values : undefined;
  const [type, setType] = useState<"DELAYED" | "CANCELLED">(typed?.type === "CANCELLED" ? "CANCELLED" : "DELAYED");

  if (state.status === "signed-out") {
    return (
      <p role="alert" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
        Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      {state.status === "recorded" && (
        <section aria-label="Recorded" role="status" className="border-l-4 border-copper bg-white px-4 py-3">
          <p className="font-semibold">
            {state.flightNumber} on {state.departureDate} is recorded as {state.type === "DELAYED" ? "delayed" : "cancelled"}.
          </p>
          <p className="mt-1 text-sm text-muted">
            {state.affectedBookings === 0
              ? "No active bookings are on this flight."
              : `${state.affectedBookings} active booking${state.affectedBookings === 1 ? " is" : "s are"} on this flight.`}{" "}
            Travellers see it on their booking page. Record it again to correct it.
          </p>
        </section>
      )}

      <form action={action} className="max-w-md space-y-4">
        <h2 className="font-display text-xl">Record a delay or cancellation</h2>
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
        <fieldset>
          <legend className="text-sm font-medium">What happened</legend>
          <div className="mt-1 flex gap-6">
            {(["DELAYED", "CANCELLED"] as const).map((option) => (
              <label key={option} className="flex items-center gap-2">
                <input
                  type="radio"
                  name="type"
                  value={option}
                  checked={type === option}
                  onChange={() => setType(option)}
                />
                {option === "DELAYED" ? "Delayed" : "Cancelled"}
              </label>
            ))}
          </div>
        </fieldset>
        {type === "DELAYED" && (
          <label className="block text-sm font-medium">
            New departure date and time (UTC)
            <input
              name="revisedDeparture"
              type="datetime-local"
              defaultValue={typed?.revisedDeparture ?? ""}
              required
              className={field}
            />
          </label>
        )}
        <label className="block text-sm font-medium">
          Reason shown to travellers (optional)
          <input
            name="reason"
            defaultValue={typed?.reason ?? ""}
            maxLength={200}
            autoComplete="off"
            className={field}
          />
          <span className="mt-1 block text-xs font-normal text-muted">No names or personal details.</span>
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
        >
          {pending ? "Recording…" : "Record"}
        </button>
        {state.status === "error" && (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        )}
      </form>
    </div>
  );
}
