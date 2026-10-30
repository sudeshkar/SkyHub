"use client";

import Link from "next/link";
import { useActionState } from "react";
import { setPassword, type SetPasswordState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

/** Where the emailed link leads (ADR-0033): choosing a password with it is what proves the address is the person's. */
export function SetPasswordForm({ token }: { token: string }) {
  const [state, action, pending] = useActionState<SetPasswordState, FormData>(setPassword, { status: "idle" });

  if (state.status === "done") {
    return (
      <section role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
        <p className="font-semibold">Your password is saved.</p>
        <p className="mt-2 text-sm">
          <Link href="/account" className="underline">Sign in</Link> with your email address and the new password.
        </p>
      </section>
    );
  }

  return (
    <form action={action} className="max-w-md space-y-4">
      <h2 className="font-display text-xl">Choose a password</h2>
      <input type="hidden" name="token" value={token} />
      <label className="block text-sm font-medium">
        New password
        <input name="password" type="password" required minLength={12} maxLength={100} autoComplete="new-password" className={field} />
        <span className="mt-1 block text-xs font-normal text-muted">At least 12 characters. A few words in a row is easy to remember and hard to guess.</span>
      </label>
      <label className="block text-sm font-medium">
        Repeat the password
        <input name="confirm" type="password" required minLength={12} maxLength={100} autoComplete="new-password" className={field} />
      </label>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Saving…" : "Save password"}
      </button>
      {state.status === "link-expired" && (
        <p role="alert" className="text-sm text-danger">
          This link no longer works: it may have been used already, or it has expired.{" "}
          <Link href="/account/forgot" className="underline">Ask for a new link</Link>
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
