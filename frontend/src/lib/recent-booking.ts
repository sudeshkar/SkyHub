import type { Segment } from "@/lib/api/booking";

/**
 * The booking just made in this browser tab, so the confirmation survives a refresh, Back and Forward, and the booking
 * page can warn before the same flights are booked again by mistake.
 *
 * It lives in session storage: this tab only, never sent anywhere, and cleared by the browser when the tab closes. It
 * holds only what the confirmation shows (the reference, the masked email, the amount, names and seats), never payment
 * details. The booking reference stays out of the address bar, where it would be kept in history and shared links.
 * When storage is refused (a private window, or storage turned off) everything still works: the confirmation page then
 * points to Manage booking.
 */
export type RecentBooking = {
  locator: string;
  /** Already masked by the booking service, as on the confirmation. */
  contactEmail: string;
  /** The amount paid, formatted for display. */
  paid: string;
  /** Which flights were booked; see tripKey. */
  trip: string;
  passengers: { firstName: string; lastName: string; seats: string[] }[];
};

const KEY = "skyhub.recent-booking";

/** A trip's flights and dates in booking order, the same for every page showing it. */
export function tripKey(segments: Segment[]): string {
  return segments.map((s) => `${s.flightNumber}_${s.departureDate}`).join(",");
}

export function rememberBooking(booking: RecentBooking): void {
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(booking));
  } catch {
    // Storage refused: the confirmation page falls back to pointing at Manage booking.
  }
}

export function forgetRecentBooking(): void {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // Nothing to forget.
  }
}

/** The stored text, for components that subscribe to it; null when there is none or storage is refused. */
export function readRecentBookingText(): string | null {
  try {
    return window.sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** Turns stored text back into a booking, or null if it is missing or not a booking. */
export function parseRecentBooking(text: string | null): RecentBooking | null {
  if (!text) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== "object") return null;
    const b = value as Record<string, unknown>;
    const ok =
      typeof b.locator === "string" &&
      typeof b.contactEmail === "string" &&
      typeof b.paid === "string" &&
      typeof b.trip === "string" &&
      Array.isArray(b.passengers);
    return ok ? (value as RecentBooking) : null;
  } catch {
    return null;
  }
}

export function readRecentBooking(): RecentBooking | null {
  return parseRecentBooking(readRecentBookingText());
}
