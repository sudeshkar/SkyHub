"use client";

import { useActionState } from "react";
import { undoEmailChange, type UndoEmailState } from "./email-actions";

/**
 * Where the link in the "your address was changed" notice leads (ADR-0054). Opening the link does nothing by itself: a person
 * presses the button, so a mail scanner that opens links cannot take an account back. The token is in a hidden field, not shown.
 */
export function UndoEmailForm({ token }: { token: string }) {
  const [state, action, pending] = useActionState<UndoEmailState, FormData>(undoEmailChange, { status: "idle" });

  if (state.status === "done") {
    return (
      <section role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
        <p className="font-semibold">Your account is yours again.</p>
        <p className="mt-2 text-sm">
          Your old email address is back on it and everyone has been signed out. We have sent a link to choose a new password to that
          address. Until you use it, nobody can sign in.
        </p>
      </section>
    );
  }

  return (
    <form action={action} className="max-w-md space-y-4">
      <h2 className="font-display text-xl">Take your account back</h2>
      <input type="hidden" name="token" value={token} />
      <p className="text-sm text-muted">
        This will put your old email address back on the SkyHub account, sign everyone out, and the password is removed, so whoever
        changed the address can no longer use it. We then send you a link to choose a new password. If you changed your address yourself,
        do not press the button.
      </p>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Working…" : "Take my account back"}
      </button>
      {state.status === "link-invalid" && (
        <p role="alert" className="text-sm text-danger">
          This link no longer works: it may have been used, or it expired (it lasts seven days), or another account now uses the
          address. Contact SkyHub support straight away.
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
