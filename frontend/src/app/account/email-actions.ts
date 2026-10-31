"use server";

import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { confirmAccountEmailChange, requestAccountEmailChange } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MAX_TOKEN = 100;
const UNAVAILABLE = "The booking service is not responding right now. Please try again.";

function waitMessage(retryAfterSeconds: number): string {
  const minutes = Math.ceil(retryAfterSeconds / 60);
  return `Too many attempts. Please try again in ${minutes} minute${minutes === 1 ? "" : "s"}.`;
}

export type ChangeEmailState =
  | { status: "idle" }
  | { status: "sent"; email: string }
  | { status: "error"; message: string; email: string }
  | { status: "signed-out" };

/**
 * Asks to move the signed-in account to another address (ADR-0039): the current password, then a link to the NEW address. Posted
 * from a form so the password never appears in a URL or a log. The answer is the same whether or not the new address can be used,
 * so this cannot be used to find out who has an account. The password is limited like sign-in, because it checks one.
 */
export async function requestEmailChange(_previous: ChangeEmailState, form: FormData): Promise<ChangeEmailState> {
  const email = String(form.get("newEmail") ?? "").trim().toLowerCase();
  const password = String(form.get("password") ?? "");
  const fail = (message: string): ChangeEmailState => ({ status: "error", message, email });

  const session = await readAccountSession();
  if (!session) return { status: "signed-out" };
  const decision = await rateLimit(POLICIES.accountSignIn);
  if (!decision.allowed) return fail(waitMessage(decision.retryAfterSeconds));
  if (email.length > 254 || !EMAIL.test(email)) return fail("Enter a valid email address.");
  if (password.length === 0 || password.length > 100) return fail("Enter your current password.");

  const outcome = await requestAccountEmailChange(session.token, email, password);
  switch (outcome.kind) {
    case "sent":
      return { status: "sent", email };
    case "password-refused":
      return fail("That password was not accepted. Check it and try again; after several wrong tries the account is locked for a while.");
    case "rejected":
      return fail(outcome.detail);
    case "unauthorized":
      await clearAccountSession(); // expired, or no longer an account: the cookie is no use any more
      return { status: "signed-out" };
    default:
      return fail(UNAVAILABLE);
  }
}

export type ConfirmEmailState =
  | { status: "idle" }
  | { status: "done" }
  | { status: "link-invalid" }
  | { status: "error"; message: string }
  | { status: "signed-out" };

/**
 * Spends the link sent to the new address, for the signed-in account (ADR-0039). Both are needed: the link proves the new mailbox
 * can be read, and the session says which account it is for. A wrong, used or expired link all look the same.
 */
export async function confirmEmailChange(_previous: ConfirmEmailState, form: FormData): Promise<ConfirmEmailState> {
  const token = String(form.get("token") ?? "");
  if (token.length === 0 || token.length > MAX_TOKEN) return { status: "link-invalid" };

  const session = await readAccountSession();
  if (!session) return { status: "signed-out" };
  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) return { status: "error", message: waitMessage(decision.retryAfterSeconds) };

  const outcome = await confirmAccountEmailChange(session.token, token);
  switch (outcome.kind) {
    case "changed":
      return { status: "done" };
    case "invalid-link":
      return { status: "link-invalid" };
    case "unauthorized":
      await clearAccountSession();
      return { status: "signed-out" };
    default:
      return { status: "error", message: UNAVAILABLE };
  }
}
