"use client";

import { useActionState } from "react";
import { staffLogin, type LoginState } from "./actions";

const field = "mt-1 block w-full rounded-md border border-sand bg-white px-3 py-2.5";

export function LoginForm() {
  const [state, action, pending] = useActionState<LoginState, FormData>(staffLogin, { status: "idle" });

  return (
    <form action={action} className="max-w-md space-y-4">
      <label className="block text-sm font-medium">
        Username
        <input
          name="username"
          defaultValue={state.status === "idle" ? "" : state.username}
          required
          maxLength={40}
          autoCapitalize="none"
          autoComplete="username"
          className={field}
        />
      </label>
      <label className="block text-sm font-medium">
        Password
        <input name="password" type="password" required maxLength={200} autoComplete="current-password" className={field} />
      </label>
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-copper px-6 py-3 font-semibold text-white hover:bg-copper-strong disabled:opacity-60"
      >
        {pending ? "Signing in…" : "Sign in"}
      </button>
      {state.status === "error" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
    </form>
  );
}
