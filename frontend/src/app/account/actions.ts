"use server";

import { redirect } from "next/navigation";
import { saveAccountSession, clearAccountSession } from "@/lib/account-session";
import { openAccountSession, requestPasswordLink, setAccountPassword } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD = 12;
const MAX_PASSWORD = 100;
const UNAVAILABLE = "The booking service is not responding right now. Please try again.";

function waitMessage(retryAfterSeconds: number): string {
  const minutes = Math.ceil(retryAfterSeconds / 60);
  return `Too many attempts. Please try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`;
}

export type LinkState = { status: "idle" } | { status: "sent"; email: string } | { status: "error"; message: string; email: string };

/**
 * Asks for an emailed link to choose a password (ADR-0033). It signs up a new traveller and resets a forgotten password alike,
 * and the answer is the same whether or not the address has an account, so nothing here says who is registered.
 */
export async function requestLink(_previous: LinkState, form: FormData): Promise<LinkState> {
  const email = String(form.get("email") ?? "").trim().toLowerCase();
  const fail = (message: string): LinkState => ({ status: "error", message, email });

  const decision = await rateLimit(POLICIES.accountLink);
  if (!decision.allowed) return fail(waitMessage(decision.retryAfterSeconds));
  if (email.length > 254 || !EMAIL.test(email)) return fail("Enter a valid email address.");

  const outcome = await requestPasswordLink(email);
  if (outcome.kind === "sent") return { status: "sent", email };
  if (outcome.kind === "rejected") return fail(outcome.detail);
  return fail(UNAVAILABLE);
}

export type SetPasswordState =
  | { status: "idle" }
  | { status: "done" }
  | { status: "link-expired" }
  | { status: "error"; message: string };

/** Spends the emailed link: the person chooses a password, which also proves the address is theirs. */
export async function setPassword(_previous: SetPasswordState, form: FormData): Promise<SetPasswordState> {
  const token = String(form.get("token") ?? "");
  const password = String(form.get("password") ?? "");
  const confirm = String(form.get("confirm") ?? "");
  const fail = (message: string): SetPasswordState => ({ status: "error", message });

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return fail(waitMessage(decision.retryAfterSeconds));
  if (password.length < MIN_PASSWORD || password.length > MAX_PASSWORD) {
    return fail(`Choose a password of ${MIN_PASSWORD} to ${MAX_PASSWORD} characters.`);
  }
  if (password !== confirm) return fail("The two passwords do not match.");

  const outcome = await setAccountPassword(token, password);
  switch (outcome.kind) {
    case "set":
      return { status: "done" };
    case "invalid-link":
      return { status: "link-expired" };
    case "rejected":
      return fail(outcome.detail);
    default:
      return fail(UNAVAILABLE);
  }
}

export type SignInState = { status: "idle" } | { status: "error"; message: string; email: string };

/**
 * Posted from a form so the password never appears in a URL or a log. A wrong password, an unknown address, an account with no
 * password yet and a locked account all get the same message, as they do in the API. On success the token goes into an httpOnly cookie.
 */
export async function signIn(_previous: SignInState, form: FormData): Promise<SignInState> {
  const email = String(form.get("email") ?? "").trim().toLowerCase();
  const password = String(form.get("password") ?? "");

  const decision = await rateLimit(POLICIES.accountSignIn);
  if (!decision.allowed) return { status: "error", message: waitMessage(decision.retryAfterSeconds), email };
  if (email.length === 0 || password.length === 0 || password.length > 200) {
    return { status: "error", message: "Enter your email address and password.", email };
  }

  const outcome = await openAccountSession(email, password);
  if (outcome.kind === "denied") return { status: "error", message: "Wrong email or password.", email };
  if (outcome.kind === "unavailable") return { status: "error", message: UNAVAILABLE, email };
  await saveAccountSession({ email: outcome.email, token: outcome.accessToken }, outcome.expiresAt);
  redirect("/account"); // outside any try/catch: redirect() works by throwing
}

/** Ends the session: the token is dropped from the browser. */
export async function signOut(): Promise<void> {
  await clearAccountSession();
  redirect("/account");
}
