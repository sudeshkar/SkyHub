"use client";

import { useActionState } from "react";
import { lookupBooking, type LookupState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

export function ManageForm() {
  const [state, action, pending] = useActionState<LookupState, FormData>(lookupBooking, { status: "idle" });

  return (
    <div className="grid gap-10 lg:grid-cols-2">
      <form action={action} className="space-y-4">
        <label className="block text-sm font-medium">
          Booking reference
          <input
            name="locator"
            defaultValue={state.status === "idle" ? "" : state.locator}
            required
            minLength={6}
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            className={`${field} font-mono uppercase tracking-widest`}
          />
        </label>
        <label className="block text-sm font-medium">
          Last name of any passenger
          <input name="lastName" required maxLength={50} autoComplete="family-name" className={field} />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
        >
          {pending ? "Finding…" : "Find booking"}
        </button>
        {state.status === "error" && (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        )}
      </form>

      {state.status === "found" && (
        <section aria-label="Your booking" className="rounded-lg border border-sand bg-white p-6">
          <p className="text-sm text-muted">Booking reference</p>
          <p className="font-mono text-2xl tracking-[0.2em]">{state.pnr.locator}</p>
          <p className="mt-1 text-sm font-semibold text-copper">
            {state.pnr.status === "CONFIRMED" ? "Confirmed" : "Cancelled"}
          </p>
          <h2 className="mt-6 font-semibold">Flights</h2>
          <ul className="mt-2 space-y-1 font-mono text-sm">
            {state.pnr.segments.map((s) => (
              <li key={s.flightNumber}>
                {s.flightNumber} · {s.departureDate}
              </li>
            ))}
          </ul>
          <h2 className="mt-6 font-semibold">Passengers</h2>
          <ul className="mt-2 space-y-1 text-sm">
            {state.pnr.passengers.map((p) => (
              <li key={`${p.firstName}-${p.lastName}-${p.seats.join()}`}>
                {p.firstName} {p.lastName} — <span className="font-mono">{p.seats.join(", ")}</span>
              </li>
            ))}
          </ul>
          <p className="mt-6 text-sm text-muted">Contact: {state.pnr.contactEmail}</p>
        </section>
      )}
    </div>
  );
}
