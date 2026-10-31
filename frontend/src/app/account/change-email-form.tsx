"use client";

import Link from "next/link";
import { useActionState } from "react";
import { requestEmailChange, type ChangeEmailState } from "./email-actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

/**
 * Asks to move the account to another address (ADR-0039). The password is asked for again, and a link goes to the new address;
 * nothing changes until it is used. The same answer is shown whether or not the new address can be used.
 */
export function ChangeEmailForm() {
  const [state, action, pending] = useActionState<ChangeEmailState, FormData>(requestEmailChange, { status: "idle" });

  if (state.status === "sent") {
    return (
      <section role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
        <p className="font-semibold">Check the new inbox.</p>
        <p className="mt-1 text-sm text-muted">
          If {state.email} can be used, we have sent it a link. Open it while you are signed in to this account. The link works once
          and for one hour. Nothing changes until you do.
        </p>
        <p className="mt-3 text-sm">
          <Link href="/account" className="underline">Back to your account</Link>
        </p>
      </section>
    );
  }

  return (
    <form action={action} className="max-w-md space-y-4">
      <h2 className="font-display text-xl">Change your email address</h2>
      <p className="text-sm text-muted">
        We will send a link to the new address to check it is yours. Your bookings are listed by email address, so afterwards this
        account will list the bookings made with the new address.
      </p>
      <label className="block text-sm font-medium">
        New email address
        <input
          name="newEmail"
          type="email"
          defaultValue={state.status === "error" ? state.email : ""}
          required
          maxLength={254}
          autoComplete="email"
          className={field}
        />
      </label>
      <label className="block text-sm font-medium">
        Current password
        <input name="password" type="password" required maxLength={100} autoComplete="current-password" className={field} />
      </label>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Sending…" : "Email me a link"}
      </button>
      {state.status === "error" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
      {state.status === "signed-out" && (
        <p role="alert" className="text-sm text-danger">
          Your session has ended. <Link href="/account" className="underline">Sign in</Link> and try again.
        </p>
      )}
    </form>
  );
}
