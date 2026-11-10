"use client";

import { useActionState } from "react";
import { undoBoarding, type UndoBoardingState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-line bg-white px-3 py-2.5";

/**
 * Takes back a boarding that was recorded by mistake (ADR-0064). For operations and administrators only: the page does not show it to
 * a gate agent, and the booking service would refuse them anyway. It asks for a reason and a confirmation because the correction is
 * kept on record with the staff member's name.
 */
export function UndoBoardingForm() {
  const [state, action, pending] = useActionState<UndoBoardingState, FormData>(undoBoarding, { status: "idle" });
  const typed = state.status === "error" ? state.values : undefined;

  if (state.status === "signed-out") {
    return (
      <p role="alert" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
        Your session has ended, or your role cannot do this. <a href="/ops" className="underline">Sign in again</a>.
      </p>
    );
  }

  return (
    <section aria-label="Undo a boarding" className="space-y-6">
      {state.status === "undone" && (
        <div role="status" className="border-l-4 border-accent bg-white px-4 py-3">
          <p className="font-semibold">
            Boarding taken back for passenger {state.passenger} on flight {state.leg} of booking {state.locator}.
          </p>
          <p className="mt-1 text-sm text-muted">They are still checked in and can board again with the same pass. Your name and reason were recorded.</p>
        </div>
      )}

      <form action={action} className="tool-form">
        <h2 className="font-display text-xl">Undo a boarding</h2>
        <p className="text-sm text-muted">
          Use this when a passenger was boarded by mistake, for example the wrong pass was scanned. They stay checked in and can board
          again. The correction is recorded with your name and the reason.
        </p>
        <label className="block text-sm font-medium">
          Booking reference
          <input
            name="locator"
            defaultValue={typed?.locator ?? ""}
            required
            maxLength={6}
            autoCapitalize="characters"
            autoComplete="off"
            placeholder="ABC234"
            className={`${field} font-mono uppercase tracking-widest`}
          />
        </label>
        <div className="flex gap-4">
          <label className="block flex-1 text-sm font-medium">
            Flight on the booking
            <input name="leg" type="number" min={1} max={999} defaultValue={typed?.leg ?? "1"} required className={field} />
          </label>
          <label className="block flex-1 text-sm font-medium">
            Passenger on the booking
            <input name="passenger" type="number" min={1} max={999} defaultValue={typed?.passenger ?? "1"} required className={field} />
          </label>
        </div>
        <label className="block text-sm font-medium">
          Reason
          <textarea name="reason" defaultValue={typed?.reason ?? ""} required maxLength={200} rows={2} className={field} />
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" name="confirm" value="yes" required />
          I am sure I want to undo this boarding
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded-md border border-accent px-6 py-3 font-semibold text-accent hover:bg-white disabled:opacity-60"
        >
          {pending ? "Undoing…" : "Undo boarding"}
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
