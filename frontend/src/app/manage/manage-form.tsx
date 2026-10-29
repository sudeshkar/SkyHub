"use client";

import { useActionState } from "react";
import { lookupBooking, type LookupState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

export function ManageForm({ notice }: { notice?: string }) {
  const [state, action, pending] = useActionState<LookupState, FormData>(lookupBooking, { status: "idle" });

  return (
    <form action={action} className="max-w-md space-y-4">
      {notice && (
        <p role="status" className="border-l-4 border-amber-line bg-amber-bg px-4 py-3 text-sm text-text">
          {notice}
        </p>
      )}
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
  );
}
