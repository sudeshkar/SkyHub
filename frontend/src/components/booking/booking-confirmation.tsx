"use client";

import Link from "next/link";
import { useMemo, useSyncExternalStore } from "react";
import { parseRecentBooking, readRecentBookingText, type RecentBooking } from "@/lib/recent-booking";

function subscribe(onChange: () => void) {
  window.addEventListener("storage", onChange);
  return () => window.removeEventListener("storage", onChange);
}

/** The booking made in this tab, or null: none, storage refused, or still on the server, which cannot see it. */
export function useRecentBooking(): RecentBooking | null {
  const text = useSyncExternalStore(subscribe, readRecentBookingText, () => null);
  return useMemo(() => parseRecentBooking(text), [text]);
}

/**
 * The confirmation page's content. After paying, the booking page is replaced by this one in the history, so Back goes to the
 * flight results and a refresh or Forward shows the confirmation again, rather than an empty seat map that makes the booking
 * look as if it failed (and invites paying twice).
 */
export function ConfirmedView() {
  const booking = useRecentBooking();
  if (!booking) {
    return (
      <section className="max-w-xl rounded-lg border border-line bg-white p-8">
        <h2 className="text-xl font-semibold">Your booking is in Manage booking</h2>
        <p className="mt-2 text-muted">
          The confirmation is not shown in this tab any more. Your booking reference is in the confirmation email; use it with a
          passenger&apos;s last name to see the booking, check in or change seats.
        </p>
        <div className="mt-6 flex flex-wrap gap-3">
          <Link href="/manage" className="btn-primary px-5 py-2.5 font-semibold">
            Manage booking
          </Link>
          <Link href="/" className="rounded-full border border-line bg-white px-5 py-2.5 hover:border-accent hover:text-accent">
            Book a flight
          </Link>
        </div>
      </section>
    );
  }
  return <BookingConfirmation booking={booking} />;
}

export function BookingConfirmation({ booking }: { booking: RecentBooking }) {
  return (
    <section aria-labelledby="done-heading" className="max-w-xl rounded-lg border border-line bg-white p-8">
      <p className="text-sm font-semibold text-accent">Booking confirmed</p>
      <h2 id="done-heading" className="mt-1 font-display text-3xl">
        Your booking reference
      </h2>
      <p
        className="mt-4 font-mono text-4xl tracking-[0.3em]"
        aria-label={`Booking reference ${booking.locator.split("").join(" ")}`}
      >
        {booking.locator}
      </p>
      <p className="mt-4 text-sm">
        Paid <span className="font-mono font-semibold">{booking.paid}</span>
      </p>
      <p className="mt-2 text-muted">
        Confirmation sent to {booking.contactEmail}. Keep this reference and a passenger&apos;s last name to manage the booking.
      </p>
      <ul className="mt-6 space-y-1 text-sm">
        {booking.passengers.map((p) => (
          <li key={`${p.firstName}-${p.lastName}-${p.seats.join()}`}>
            {p.firstName} {p.lastName} — <span className="font-mono">{p.seats.join(", ")}</span>
          </li>
        ))}
      </ul>
      <div className="mt-8 flex flex-wrap gap-3">
        <Link href="/manage" className="btn-primary px-5 py-2.5 font-semibold">
          Manage this booking
        </Link>
        <Link href="/" className="rounded-full border border-line bg-white px-5 py-2.5 hover:border-accent hover:text-accent">
          Book another flight
        </Link>
      </div>
    </section>
  );
}
