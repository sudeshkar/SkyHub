"use client";

import Link from "next/link";
import { useActionState } from "react";
import { requestLink, type LinkState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

const WORDS = {
  "sign-up": { heading: "Create an account", lead: "Enter your email address and we will send you a link to choose a password." },
  reset: { heading: "Forgot your password?", lead: "Enter your email address and we will send you a link to choose a new one." },
} as const;

/**
 * Asks for the emailed link (ADR-0033). One form for signing up and for resetting a password: the answer is the same whether or
 * not the address already has an account, so this page cannot be used to find out who is registered.
 */
export function LinkForm({ mode }: { mode: keyof typeof WORDS }) {
  const [state, action, pending] = useActionState<LinkState, FormData>(requestLink, { status: "idle" });
  const words = WORDS[mode];

  if (state.status === "sent") {
    return (
      <section role="status" className="max-w-md border-l-4 border-copper bg-white px-4 py-3">
        <p className="font-semibold">Check your inbox.</p>
        <p className="mt-1 text-sm text-muted">
          If {state.email} is a valid address, we have sent it a link to choose a password. The link works once and for one hour.
        </p>
        <p className="mt-3 text-sm">
          <Link href="/account" className="underline">Back to sign in</Link>
        </p>
      </section>
    );
  }

  return (
    <form action={action} className="max-w-md space-y-4">
      <h2 className="font-display text-xl">{words.heading}</h2>
      <p className="text-sm text-muted">{words.lead}</p>
      <label className="block text-sm font-medium">
        Email address
        <input
          name="email"
          type="email"
          defaultValue={state.status === "error" ? state.email : ""}
          required
          maxLength={254}
          autoComplete="email"
          className={field}
        />
      </label>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Sending…" : "Email me a link"}
      </button>
      {state.status === "error" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
    </form>
  );
}
