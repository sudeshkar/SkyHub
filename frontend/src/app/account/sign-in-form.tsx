"use client";

import Link from "next/link";
import { useActionState } from "react";
import { signIn, type SignInState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

export function SignInForm() {
  const [state, action, pending] = useActionState<SignInState, FormData>(signIn, { status: "idle" });

  return (
    <form action={action} className="max-w-md space-y-4">
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
      <label className="block text-sm font-medium">
        Password
        <input name="password" type="password" required maxLength={200} autoComplete="current-password" className={field} />
      </label>
      <button type="submit" disabled={pending} className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60">
        {pending ? "Signing in…" : "Sign in"}
      </button>
      {state.status === "error" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
      <p className="text-sm text-muted">
        New here? <Link href="/account/sign-up" className="underline">Create an account</Link>.{" "}
        <Link href="/account/forgot" className="underline">Forgot your password?</Link>
      </p>
    </form>
  );
}
