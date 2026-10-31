"use server";

import { redirect } from "next/navigation";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { openMyBookingSession } from "@/lib/api/booking";
import { saveSession } from "@/lib/manage-session";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

const LOCATOR = /^[A-Z0-9]{6}$/;

/**
 * Opens one of the signed-in traveller's bookings without its last name (ADR-0034). The account token (in a cookie only this
 * server reads) proves whose booking it is; what the booking page then holds is the ordinary manage token for that one booking,
 * so nothing after this knows how the traveller arrived. Every failure goes back to the account page: a booking that is not the
 * account's is indistinguishable from one that does not exist, here as in the API.
 */
export async function openMyBooking(locator: string): Promise<void> {
  const session = await readAccountSession();
  if (!session) redirect("/account");
  if (!LOCATOR.test(locator)) redirect("/account?problem=open");

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) redirect("/account?problem=wait");

  const result = await openMyBookingSession(session.token, locator);
  if (result.kind === "unauthorized") {
    await clearAccountSession(); // expired, or no longer an account: the cookie is no use any more
    redirect("/account");
  }
  if (result.kind !== "opened") redirect("/account?problem=open");
  await saveSession({ locator, token: result.accessToken }, result.expiresAt);
  redirect("/manage"); // outside any try/catch: redirect() works by throwing
}
