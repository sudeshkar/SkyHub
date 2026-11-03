"use server";

import { redirect } from "next/navigation";
import { clearAccountSession, readAccountSession } from "@/lib/account-session";
import { removeSavedPassenger, saveMyPassenger } from "@/lib/api/booking";
import { POLICIES, rateLimit } from "@/lib/rate-limit";

const MAX_NAME = 50;

/**
 * Saves a name to the signed-in account (ADR-0078). A form post, so the names are never in an address; the page shows the list again,
 * with what went wrong in the address as a short word and never a name. Saving a name that is already kept is the same as saving it once.
 */
export async function addSavedPassenger(form: FormData): Promise<void> {
  const session = await readAccountSession();
  if (!session) redirect("/account");

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) redirect("/account?problem=wait");

  const firstName = String(form.get("firstName") ?? "").trim();
  const lastName = String(form.get("lastName") ?? "").trim();
  if (firstName.length === 0 || firstName.length > MAX_NAME || lastName.length === 0 || lastName.length > MAX_NAME) {
    redirect("/account?problem=passenger-invalid");
  }

  const outcome = await saveMyPassenger(session.token, firstName, lastName);
  if (outcome.kind === "saved" || outcome.kind === "already-saved") redirect("/account");
  if (outcome.kind === "full") redirect("/account?problem=passengers-full");
  if (outcome.kind === "rejected") redirect("/account?problem=passenger-invalid");
  if (outcome.kind === "unauthorized") {
    await clearAccountSession(); // expired, or refused: this cookie is no use any more
    redirect("/account");
  }
  redirect("/account?problem=passenger-failed");
}

/** Removes a saved name by its number. One that is already gone is done, so a double press or a stale page is harmless. */
export async function deleteSavedPassenger(form: FormData): Promise<void> {
  const session = await readAccountSession();
  if (!session) redirect("/account");

  const decision = await rateLimit(POLICIES.manage);
  if (!decision.allowed) redirect("/account?problem=wait");

  const raw = String(form.get("id") ?? "");
  if (!/^[1-9]\d{0,14}$/.test(raw)) redirect("/account?problem=passenger-failed");

  const outcome = await removeSavedPassenger(session.token, Number(raw));
  if (outcome.kind === "removed" || outcome.kind === "not-found") redirect("/account");
  if (outcome.kind === "unauthorized") {
    await clearAccountSession();
    redirect("/account");
  }
  redirect("/account?problem=passenger-failed");
}
