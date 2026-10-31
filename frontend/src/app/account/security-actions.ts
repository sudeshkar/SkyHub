"use server";

import { redirect } from "next/navigation";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { endAllAccountSessions } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

/**
 * Ends every session of the signed-in account on every device, this one included (ADR-0047), and drops this browser's cookie. It
 * asks for no password: it can only make the account harder to use, never easier to take.
 *
 * <p>When the booking service cannot be reached the cookie is kept and the page says so, because a person who believes they have
 * signed out everywhere must not be told so when they have not. A session the service no longer accepts has nothing left to end, so
 * that too leaves this browser signed out.
 */
export async function signOutEverywhere(): Promise<void> {
  const session = await readAccountSession();
  if (!session) redirect("/account");

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) redirect("/account?problem=wait");

  const outcome = await endAllAccountSessions(session.token);
  if (outcome.kind === "unavailable") redirect("/account?problem=signout");
  await clearAccountSession(); // ended, or already ended: either way this browser is no longer signed in
  redirect("/account"); // outside any try/catch: redirect() works by throwing
}
