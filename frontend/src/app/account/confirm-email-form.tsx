"use client";

import Link from "next/link";
import { useActionState } from "react";
import { confirmEmailChange, type ConfirmEmailState } from "./email-actions";

/**
 * Where the link sent to the new address leads (ADR-0039). Opening the link does nothing by itself: a person presses the button,
 * so a mail scanner that opens links cannot move an account. The token is in a hidden field, not shown on the page.
 */
export function ConfirmEmailForm({ token }: { token: string }) {
  const [state, action, pending] = useActionState<ConfirmEmailState, FormData>(confirmEmailChange, { status: "idle" });

  if (state.status === "done") {
    return (
      <section role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
        <p className="font-semibold">Your new email address is saved.</p>
        <p className="mt-2 text-sm">
          For your safety you have been signed out everywhere. <Link href="/account" className="underline">Sign in</Link> again with the
          new address; the account will then list the bookings made with it.
        </p>
      </section>
    );
  }

  return (
    <form action={action} className="max-w-md space-y-4">
      <h2 className="font-display text-xl">Confirm your new email address</h2>
      <input type="hidden" name="token" value={token} />
      <p className="text-sm text-muted">
        This moves the account you are signed in to onto the address this link was sent to. Every session on this account, including
        this one, will end, and you will sign in again with the new address. The account will then list the bookings made with it
        instead of bookings made with the old address (those can still be opened with their reference and last name).
      </p>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Confirming…" : "Confirm the change"}
      </button>
      {state.status === "link-invalid" && (
        <p role="alert" className="text-sm text-danger">
          This link no longer works: it may have been used, or it expired. <Link href="/account/change-email" className="underline">Change email address</Link> to get a new one.
        </p>
      )}
      {state.status === "signed-out" && (
        <p role="alert" className="text-sm text-danger">
          <Link href="/account" className="underline">Sign in</Link> to the account you are moving, then open the link in your email again.
        </p>
      )}
      {state.status === "error" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
    </form>
  );
}
