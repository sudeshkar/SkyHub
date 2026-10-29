"use server";

import { getPnr, type Pnr } from "@/lib/api/booking";

/** `locator` is echoed back so the field keeps its value after React resets the form. */
export type LookupState =
  | { status: "idle" }
  | { status: "found"; pnr: Pnr; locator: string }
  | { status: "error"; message: string; locator: string };

const LOCATOR = /^[A-Z0-9]{6}$/;

/**
 * Posted from a form so the last name never appears in a URL, browser history or server logs.
 * Unknown reference and wrong name get the same message, so neither can be probed.
 */
export async function lookupBooking(_previous: LookupState, form: FormData): Promise<LookupState> {
  const locator = String(form.get("locator") ?? "").trim().toUpperCase();
  const lastName = String(form.get("lastName") ?? "").trim();
  if (!LOCATOR.test(locator) || lastName.length === 0 || lastName.length > 50) {
    return {
      status: "error",
      message: "Enter your 6-character booking reference and a passenger's last name.",
      locator,
    };
  }

  const result = await getPnr(locator, lastName);
  if (result === "not-found") {
    return { status: "error", message: "We couldn't find a booking with that reference and last name.", locator };
  }
  if (result === "unavailable") {
    return { status: "error", message: "Booking lookup is not responding right now. Please try again.", locator };
  }
  return { status: "found", pnr: result, locator };
}
