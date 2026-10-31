import Link from "next/link";
import { openMyBooking } from "@/app/account/my-bookings-actions";
import type { MyBooking } from "@/lib/api/booking";
import { formatSearchDate } from "@/lib/format";

export type BookingsFilter = "all" | "upcoming" | "past";

/** Reads the filter from the address. Anything that is not exactly one of the three is "all": a made-up value shows everything. */
export function parseBookingsFilter(value: string | string[] | undefined): BookingsFilter {
  return value === "upcoming" || value === "past" ? value : "all";
}

const FILTERS: { value: BookingsFilter; label: string }[] = [
  { value: "all", label: "All" },
  { value: "upcoming", label: "Upcoming" },
  { value: "past", label: "Past and cancelled" },
];

/** The account page's address for a filter and, optionally, a page: all is the plain address, so it stays short. */
function accountHref(when: BookingsFilter, cursor?: string): string {
  const params: string[] = [];
  if (cursor) params.push(`cursor=${encodeURIComponent(cursor)}`);
  if (when !== "all") params.push(`when=${when}`);
  return params.length > 0 ? `/account?${params.join("&")}` : "/account";
}

/**
 * One page of the traveller's bookings, newest first (ADR-0038), optionally only upcoming or only past and cancelled ones (ADR-0042).
 * Opening one is a form post, so no reference or token appears in an address; the only things in the address are the filter and the
 * page cursor, which holds a time and the reference of a booking already shown.
 */
export function MyBookings({ bookings, nextCursor, firstPage, when = "all" }: {
  bookings: MyBooking[];
  nextCursor: string | null;
  firstPage: boolean;
  when?: BookingsFilter;
}) {
  const filters = (
    <nav aria-label="Which bookings to show" className="mt-3 flex flex-wrap gap-4 text-sm">
      {FILTERS.map(({ value, label }) => (
        <Link key={value} href={accountHref(value)} aria-current={value === when ? "page" : undefined}
          className={value === when ? "font-semibold underline" : "underline"}>
          {label}
        </Link>
      ))}
    </nav>
  );
  if (bookings.length === 0) {
    return (
      <>
        {filters}
        {firstPage ? (
          <p className="mt-2 text-sm text-muted">
            {when === "upcoming"
              ? "You have no upcoming bookings."
              : when === "past"
                ? "You have no past or cancelled bookings."
                : "You have no bookings yet. Bookings made with this email address appear here, including ones made before you signed up."}
          </p>
        ) : (
          <p className="mt-2 text-sm text-muted">
            There are no older bookings. <Link href={accountHref(when)} className="underline">Newest bookings</Link>
          </p>
        )}
      </>
    );
  }
  return (
    <>
      {filters}
      <ul className="mt-3 divide-y divide-sand rounded-lg border border-sand bg-white">
        {bookings.map((booking) => (
          <li key={booking.locator} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <div>
              <p className="font-mono text-lg tracking-wider">{booking.locator}</p>
              <p className="text-sm text-muted">
                {booking.segments.map((s) => `${s.flightNumber} · ${formatSearchDate(s.departureDate)}`).join("  →  ")}
              </p>
              <p className="text-sm text-muted">
                {booking.passengerCount} {booking.passengerCount === 1 ? "passenger" : "passengers"}
                {booking.status === "CANCELLED" ? <strong className="ml-2 text-danger">Cancelled</strong> : null}
              </p>
            </div>
            <form action={openMyBooking.bind(null, booking.locator)}>
              <button
                type="submit"
                aria-label={`Open booking ${booking.locator}`}
                className="rounded-md border border-sand px-4 py-2 text-sm hover:bg-sand/40"
              >
                Open
              </button>
            </form>
          </li>
        ))}
      </ul>
      <nav aria-label="Pages of bookings" className="mt-3 flex flex-wrap gap-4 text-sm">
        {firstPage ? null : <Link href={accountHref(when)} className="underline">Newest bookings</Link>}
        {nextCursor ? <Link href={accountHref(when, nextCursor)} className="underline">Older bookings</Link> : null}
      </nav>
    </>
  );
}
